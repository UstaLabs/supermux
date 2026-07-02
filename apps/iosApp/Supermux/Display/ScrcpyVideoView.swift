#if canImport(UIKit)
import UIKit
#else
import AppKit
#endif
import AVFoundation
import CoreMedia
import VideoToolbox

/// Renders the scrcpy H.264 stream with `AVSampleBufferDisplayLayer`. Each Annex-B
/// frame is split into NAL units; SPS/PPS (types 7/8) seed a `CMVideoFormatDescription`,
/// slice NALs are repackaged into AVCC (4-byte big-endian length prefixes), wrapped in a
/// `CMBlockBuffer` + `CMSampleBuffer` (display-immediately), and enqueued. VideoToolbox
/// owns the decode inside the layer — no explicit `VTDecompressionSession` needed.
///
/// A pure namespace: the `SampleBufferView` (backing view) and `Coordinator` (decoder feed)
/// are created + kept warm by a `ScrcpyHost` in `BrokerSession`, then re-parented by
/// `ScrcpySurfaceView`.
enum ScrcpyVideoView {

    // MARK: - Backing view (its layer IS an AVSampleBufferDisplayLayer)

    final class SampleBufferView: PlatformView {
        #if canImport(UIKit)
        override class var layerClass: AnyClass { AVSampleBufferDisplayLayer.self }
        #endif
        // On UIKit `layer` is non-optional; on AppKit the layer-hosting init below installs
        // the display layer before this is read, so the force-cast is safe on both.
        var displayLayer: AVSampleBufferDisplayLayer { layer as! AVSampleBufferDisplayLayer }

        #if canImport(UIKit)
        override func layoutSubviews() {
            super.layoutSubviews()
            displayLayer.videoGravity = .resizeAspect
        }
        #else
        // AppKit has no `layerClass`: host the AVSampleBufferDisplayLayer directly. The layer
        // (a CALayer subclass) tracks the view's bounds and `.resizeAspect` centers + fits the
        // video, so no drawableSize/scale bookkeeping is needed (unlike the Metal view). The
        // decoded frame resolution — not `contentsScale` — drives sharpness here.
        override init(frame frameRect: NSRect) {
            super.init(frame: frameRect)
            layer = AVSampleBufferDisplayLayer()
            wantsLayer = true
            displayLayer.videoGravity = .resizeAspect
        }
        required init?(coder: NSCoder) {
            super.init(coder: coder)
            layer = AVSampleBufferDisplayLayer()
            wantsLayer = true
            displayLayer.videoGravity = .resizeAspect
        }
        #endif
    }

    // MARK: - Decoder feed

    @MainActor
    final class Coordinator {
        private weak var layer: AVSampleBufferDisplayLayer?
        private var formatDesc: CMVideoFormatDescription?
        private var sps: [UInt8]?
        private var pps: [UInt8]?
        private var sawKeyframe = false

        func attach(layer: AVSampleBufferDisplayLayer) {
            self.layer = layer
            layer.videoGravity = .resizeAspect
        }

        /// Feed one Annex-B access unit. `isKey` marks an IDR (SPS/PPS prepended upstream).
        func enqueue(isKey: Bool, annexB: [UInt8]) {
            guard let layer else { return }
            recoverIfNeeded(layer)

            let nals = Self.splitAnnexB(annexB)
            guard !nals.isEmpty else { return }

            // Pull any new SPS/PPS out of this access unit and (re)build the format desc.
            var sliceNals: [[UInt8]] = []
            for nal in nals {
                guard let first = nal.first else { continue }
                let type = first & 0x1F
                switch type {
                case 7: sps = nal              // SPS
                case 8: pps = nal              // PPS
                default: sliceNals.append(nal) // VCL slices (and SEI etc.)
                }
            }
            if sps != nil, pps != nil, formatDesc == nil {
                rebuildFormatDescription()
            }

            if isKey { sawKeyframe = true }
            // Drop everything until the first keyframe + a valid format description.
            guard sawKeyframe, let fmt = formatDesc, !sliceNals.isEmpty else { return }

            guard let block = Self.makeBlockBuffer(fromAVCC: sliceNals) else { return }
            guard let sample = Self.makeSampleBuffer(block: block, format: fmt) else { return }
            Self.setDisplayImmediately(sample)

            layer.sampleBufferRenderer.enqueue(sample)
        }

        private func rebuildFormatDescription() {
            guard let sps, let pps else { return }
            sps.withUnsafeBufferPointer { spsBuf in
                pps.withUnsafeBufferPointer { ppsBuf in
                    let ptrs: [UnsafePointer<UInt8>] = [spsBuf.baseAddress!, ppsBuf.baseAddress!]
                    let sizes: [Int] = [sps.count, pps.count]
                    ptrs.withUnsafeBufferPointer { ptrsBuf in
                        sizes.withUnsafeBufferPointer { sizesBuf in
                            var fmt: CMFormatDescription?
                            let status = CMVideoFormatDescriptionCreateFromH264ParameterSets(
                                allocator: kCFAllocatorDefault,
                                parameterSetCount: 2,
                                parameterSetPointers: ptrsBuf.baseAddress!,
                                parameterSetSizes: sizesBuf.baseAddress!,
                                nalUnitHeaderLength: 4,
                                formatDescriptionOut: &fmt)
                            if status == noErr { self.formatDesc = fmt }
                        }
                    }
                }
            }
        }

        /// If the renderer failed (e.g. backgrounded), flush + reset so the next keyframe
        /// re-establishes decode. The broker replays a keyframe on reconnect.
        private func recoverIfNeeded(_ layer: AVSampleBufferDisplayLayer) {
            let renderer = layer.sampleBufferRenderer
            if renderer.status == .failed || renderer.requiresFlushToResumeDecoding {
                renderer.flush()
                sawKeyframe = false
            }
        }

        // MARK: - Annex-B → AVCC helpers (static; no actor state)

        /// Split a buffer on Annex-B start codes (`00 00 01` or `00 00 00 01`) into raw NAL
        /// units (start code stripped).
        static func splitAnnexB(_ data: [UInt8]) -> [[UInt8]] {
            var nals: [[UInt8]] = []
            let n = data.count
            var i = 0
            // Find the first start code.
            var start = -1
            while i + 3 <= n {
                if data[i] == 0, data[i + 1] == 0, data[i + 2] == 1 {
                    start = i + 3
                    i += 3
                    break
                }
                i += 1
            }
            if start < 0 { return [] }
            while i + 3 <= n {
                if data[i] == 0, data[i + 1] == 0, data[i + 2] == 1 {
                    // End of the current NAL: trim a trailing 0 belonging to a 4-byte start code.
                    var end = i
                    if end > start, data[end - 1] == 0 { end -= 1 }
                    if end > start { nals.append(Array(data[start..<end])) }
                    start = i + 3
                    i += 3
                } else {
                    i += 1
                }
            }
            if start < n {
                var end = n
                if end > start, data[end - 1] == 0 { end -= 1 }
                if end > start { nals.append(Array(data[start..<end])) }
            }
            return nals
        }

        /// Pack NAL units into one AVCC buffer: each prefixed with a 4-byte big-endian
        /// length, concatenated, wrapped in a `CMBlockBuffer`.
        static func makeBlockBuffer(fromAVCC nals: [[UInt8]]) -> CMBlockBuffer? {
            var avcc = [UInt8]()
            avcc.reserveCapacity(nals.reduce(0) { $0 + $1.count + 4 })
            for nal in nals {
                let len = UInt32(nal.count)
                avcc.append(UInt8((len >> 24) & 0xFF))
                avcc.append(UInt8((len >> 16) & 0xFF))
                avcc.append(UInt8((len >> 8) & 0xFF))
                avcc.append(UInt8(len & 0xFF))
                avcc.append(contentsOf: nal)
            }

            var block: CMBlockBuffer?
            // Allocate an owned, zeroed block then fill it via replaceDataBytes so we don't
            // depend on `avcc`'s lifetime after this function returns.
            let createStatus = CMBlockBufferCreateWithMemoryBlock(
                allocator: kCFAllocatorDefault,
                memoryBlock: nil,
                blockLength: avcc.count,
                blockAllocator: kCFAllocatorDefault,
                customBlockSource: nil,
                offsetToData: 0,
                dataLength: avcc.count,
                flags: CMBlockBufferFlags(),
                blockBufferOut: &block)
            guard createStatus == kCMBlockBufferNoErr, let block else { return nil }
            let fillStatus = avcc.withUnsafeBytes { raw -> OSStatus in
                guard let base = raw.baseAddress else { return -1 }
                return CMBlockBufferReplaceDataBytes(
                    with: base, blockBuffer: block, offsetIntoDestination: 0, dataLength: avcc.count)
            }
            guard fillStatus == kCMBlockBufferNoErr else { return nil }
            return block
        }

        static func makeSampleBuffer(block: CMBlockBuffer, format: CMVideoFormatDescription) -> CMSampleBuffer? {
            var sample: CMSampleBuffer?
            var sizes = [CMBlockBufferGetDataLength(block)]
            // No timing: decode/present immediately (live stream).
            var timing = CMSampleTimingInfo(duration: .invalid,
                                            presentationTimeStamp: .invalid,
                                            decodeTimeStamp: .invalid)
            let status = CMSampleBufferCreateReady(
                allocator: kCFAllocatorDefault,
                dataBuffer: block,
                formatDescription: format,
                sampleCount: 1,
                sampleTimingEntryCount: 1,
                sampleTimingArray: &timing,
                sampleSizeEntryCount: 1,
                sampleSizeArray: &sizes,
                sampleBufferOut: &sample)
            guard status == noErr else { return nil }
            return sample
        }

        /// Set the display-immediately attachment on the (single) sample. The array holds
        /// opaque `CFMutableDictionary` elements, so read via `CFArrayGetValueAtIndex` +
        /// `unsafeBitCast` (the standard CoreMedia idiom — a Swift `as?` cast is unreliable
        /// for opaque CF types).
        static func setDisplayImmediately(_ sample: CMSampleBuffer) {
            guard let attachments = CMSampleBufferGetSampleAttachmentsArray(
                sample, createIfNecessary: true),
                  CFArrayGetCount(attachments) > 0 else { return }
            let raw = CFArrayGetValueAtIndex(attachments, 0)
            let dict = unsafeBitCast(raw, to: CFMutableDictionary.self)
            CFDictionarySetValue(
                dict,
                Unmanaged.passUnretained(kCMSampleAttachmentKey_DisplayImmediately).toOpaque(),
                Unmanaged.passUnretained(kCFBooleanTrue).toOpaque())
        }
    }
}
