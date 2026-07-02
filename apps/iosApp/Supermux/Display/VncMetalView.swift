#if canImport(UIKit)
import UIKit
#else
import AppKit
#endif
import Shared
import Metal
import QuartzCore
import simd

/// Renders the VNC framebuffer with Metal. A single BGRA `MTLTexture` holds the whole
/// framebuffer; each `[VncRect]` update blits the changed rects into it and redraws a
/// full-screen textured quad (aspect-fit / letterboxed) to a `CAMetalLayer`.
///
/// Pixel format is pinned to `.bgra8Unorm` to match `VncClient`'s 32-bit BGRA output,
/// so a Raw rect is a straight `texture.replace`. CopyRect is handled on a CPU mirror of
/// the framebuffer (v1 simplicity) and the destination region is re-uploaded.
///
/// A pure namespace: the `MetalLayerView` (backing view) and `Coordinator` (renderer) are
/// created + kept warm by a `VncHost` in `BrokerSession`, then re-parented by
/// `VncSurfaceView`. The host calls `Coordinator.resize` from `VncSession.size` before each
/// update, so allocation no longer rides a SwiftUI `updateUIView`.
enum VncMetalView {

    // MARK: - Backing view (its layer IS a CAMetalLayer)

    final class MetalLayerView: PlatformView {
        #if canImport(UIKit)
        override class var layerClass: AnyClass { CAMetalLayer.self }
        #endif
        // On UIKit `layer` is non-optional; on AppKit the layer-hosting setup below installs
        // the `CAMetalLayer` before this is ever read, so the force-cast is safe on both.
        var metalLayer: CAMetalLayer { layer as! CAMetalLayer }

        #if canImport(UIKit)
        override func layoutSubviews() {
            super.layoutSubviews()
            // Keep the drawable backing store in sync with the view's pixel size.
            // `traitCollection.displayScale` is the iOS 26-clean source (UIScreen.main is
            // deprecated); it can be 0 before the view is in a hierarchy — fall back to 2.
            var scale = traitCollection.displayScale
            if scale <= 0 { scale = window?.windowScene?.screen.scale ?? 2 }
            metalLayer.contentsScale = scale
            metalLayer.drawableSize = CGSize(width: bounds.width * scale,
                                             height: bounds.height * scale)
        }
        #else
        // AppKit has no `layerClass`: make the view layer-hosting by installing the
        // CAMetalLayer BEFORE `wantsLayer` (Apple's documented order for a custom layer).
        override init(frame frameRect: NSRect) {
            super.init(frame: frameRect)
            layer = CAMetalLayer()
            wantsLayer = true
        }
        required init?(coder: NSCoder) {
            super.init(coder: coder)
            layer = CAMetalLayer()
            wantsLayer = true
        }

        // `layout()` is the mac analog of `layoutSubviews` (fires on resize); pair it with
        // `viewDidMoveToWindow` so the scale is re-read once a real window (and its
        // backingScaleFactor) is known.
        override func layout() {
            super.layout()
            updateDrawableSize()
        }
        override func viewDidMoveToWindow() {
            super.viewDidMoveToWindow()
            updateDrawableSize()
        }
        // Fires when the window's backing scale changes (e.g. dragged between a Retina and
        // a non-Retina display) — without it contentsScale/drawableSize would go stale.
        override func viewDidChangeBackingProperties() {
            super.viewDidChangeBackingProperties()
            updateDrawableSize()
        }
        // Live-resize insurance: guarantees the drawable tracks the frame even if a SwiftUI
        // resize path ever skips `layout()`.
        override func setFrameSize(_ newSize: NSSize) {
            super.setFrameSize(newSize)
            updateDrawableSize()
        }

        private func updateDrawableSize() {
            // NSView has no `traitCollection`; the backing scale comes from the window (or the
            // main screen before the view is placed), same fallback-to-2 as the iOS path.
            let scale = window?.backingScaleFactor ?? NSScreen.main?.backingScaleFactor ?? 2
            metalLayer.contentsScale = scale
            metalLayer.drawableSize = CGSize(width: bounds.width * scale,
                                             height: bounds.height * scale)
        }
        #endif
    }

    // MARK: - Renderer

    @MainActor
    final class Coordinator {
        private let device: MTLDevice?
        private let queue: MTLCommandQueue?
        private var pipeline: MTLRenderPipelineState?
        private var sampler: MTLSamplerState?
        private weak var layer: CAMetalLayer?

        /// The framebuffer texture and a CPU mirror of its BGRA bytes (for CopyRect).
        private var texture: MTLTexture?
        private var mirror: [UInt8] = []
        private var fbWidth = 0
        private var fbHeight = 0

        init() {
            let dev = MTLCreateSystemDefaultDevice()
            self.device = dev
            self.queue = dev?.makeCommandQueue()
            buildPipeline()
        }

        func attach(layer: CAMetalLayer) {
            self.layer = layer
            layer.device = device
            layer.pixelFormat = .bgra8Unorm
            layer.framebufferOnly = false
            layer.isOpaque = true
        }

        // MARK: Pipeline (shader source compiled at runtime → no .metal bundle entry needed)

        private func buildPipeline() {
            guard let device else { return }
            let src = """
            #include <metal_stdlib>
            using namespace metal;
            struct VOut { float4 pos [[position]]; float2 uv; };
            // Full-screen triangle-strip quad with an aspect-fit scale in `rect` (xy=scale).
            vertex VOut vmux_vtx(uint vid [[vertex_id]], constant float2 &scale [[buffer(0)]]) {
                float2 corners[4] = { float2(-1.0, -1.0), float2(1.0, -1.0),
                                      float2(-1.0,  1.0), float2(1.0,  1.0) };
                float2 uvs[4]     = { float2(0.0, 1.0), float2(1.0, 1.0),
                                      float2(0.0, 0.0), float2(1.0, 0.0) };
                VOut o;
                o.pos = float4(corners[vid] * scale, 0.0, 1.0);
                o.uv = uvs[vid];
                return o;
            }
            fragment float4 vmux_frag(VOut in [[stage_in]],
                                      texture2d<float> tex [[texture(0)]],
                                      sampler smp [[sampler(0)]]) {
                return tex.sample(smp, in.uv);
            }
            """
            do {
                let lib = try device.makeLibrary(source: src, options: nil)
                let desc = MTLRenderPipelineDescriptor()
                desc.vertexFunction = lib.makeFunction(name: "vmux_vtx")
                desc.fragmentFunction = lib.makeFunction(name: "vmux_frag")
                desc.colorAttachments[0].pixelFormat = .bgra8Unorm
                pipeline = try device.makeRenderPipelineState(descriptor: desc)
                let sdesc = MTLSamplerDescriptor()
                sdesc.minFilter = .linear
                sdesc.magFilter = .linear
                sampler = device.makeSamplerState(descriptor: sdesc)
            } catch {
                // Pipeline failed to build — renderer becomes a no-op; pane still shows chrome.
                pipeline = nil
            }
        }

        // MARK: Allocation

        func resize(width: Int, height: Int) {
            guard width > 0, height > 0 else { return }
            guard width != fbWidth || height != fbHeight else { return }
            guard let device else { return }
            let desc = MTLTextureDescriptor.texture2DDescriptor(
                pixelFormat: .bgra8Unorm, width: width, height: height, mipmapped: false)
            desc.usage = [.shaderRead]
            desc.storageMode = .shared
            texture = device.makeTexture(descriptor: desc)
            fbWidth = width
            fbHeight = height
            mirror = [UInt8](repeating: 0, count: width * height * 4)
        }

        // MARK: Apply a framebuffer update

        func applyUpdate(_ rects: [VncRect]) {
            guard let texture else { return }
            for rect in rects {
                let x = Int(rect.x), y = Int(rect.y)
                let w = Int(rect.width), h = Int(rect.height)
                guard w > 0, h > 0, x >= 0, y >= 0, x + w <= fbWidth, y + h <= fbHeight else { continue }

                if rect.isCopy {
                    copyRect(srcX: Int(rect.srcX), srcY: Int(rect.srcY),
                             dstX: x, dstY: y, w: w, h: h, texture: texture)
                } else {
                    let bytes = rect.bgra.toUInt8()
                    guard bytes.count >= w * h * 4 else { continue }
                    uploadRaw(bytes, x: x, y: y, w: w, h: h, texture: texture)
                }
            }
            draw()
        }

        private func uploadRaw(_ bytes: [UInt8], x: Int, y: Int, w: Int, h: Int, texture: MTLTexture) {
            let bpr = w * 4
            let region = MTLRegionMake2D(x, y, w, h)
            bytes.withUnsafeBytes { raw in
                guard let base = raw.baseAddress else { return }
                texture.replace(region: region, mipmapLevel: 0, withBytes: base, bytesPerRow: bpr)
            }
            // Keep the CPU mirror coherent so a later CopyRect reads correct source pixels.
            let dstBpr = fbWidth * 4
            mirror.withUnsafeMutableBytes { dst in
                bytes.withUnsafeBytes { src in
                    guard let dstBase = dst.baseAddress, let srcBase = src.baseAddress else { return }
                    for row in 0..<h {
                        let dstOff = (y + row) * dstBpr + x * 4
                        let srcOff = row * bpr
                        memcpy(dstBase + dstOff, srcBase + srcOff, bpr)
                    }
                }
            }
        }

        /// CopyRect: move a rectangle within the framebuffer. We copy on the CPU mirror
        /// (handles overlap via a temp buffer) then re-upload the destination region.
        private func copyRect(srcX: Int, srcY: Int, dstX: Int, dstY: Int, w: Int, h: Int, texture: MTLTexture) {
            guard srcX >= 0, srcY >= 0, srcX + w <= fbWidth, srcY + h <= fbHeight else { return }
            let bpr = w * 4
            let fbBpr = fbWidth * 4
            var tmp = [UInt8](repeating: 0, count: w * h * 4)
            mirror.withUnsafeBytes { src in
                tmp.withUnsafeMutableBytes { t in
                    guard let srcBase = src.baseAddress, let tBase = t.baseAddress else { return }
                    for row in 0..<h {
                        let srcOff = (srcY + row) * fbBpr + srcX * 4
                        memcpy(tBase + row * bpr, srcBase + srcOff, bpr)
                    }
                }
            }
            // tmp now holds the source pixels contiguously → reuse the raw upload path.
            uploadRaw(tmp, x: dstX, y: dstY, w: w, h: h, texture: texture)
        }

        // MARK: Draw

        func draw() {
            guard let layer, let queue, let pipeline, let sampler, let texture,
                  fbWidth > 0, fbHeight > 0,
                  let drawable = layer.nextDrawable() else { return }

            // Aspect-fit the framebuffer into the drawable (letterbox).
            let dw = Double(layer.drawableSize.width)
            let dh = Double(layer.drawableSize.height)
            guard dw > 0, dh > 0 else { return }
            let texAspect = Double(fbWidth) / Double(fbHeight)
            let viewAspect = dw / dh
            var scale = SIMD2<Float>(1, 1)
            if texAspect > viewAspect {
                scale.y = Float(viewAspect / texAspect)   // pillarbox top/bottom
            } else {
                scale.x = Float(texAspect / viewAspect)   // letterbox left/right
            }

            let pass = MTLRenderPassDescriptor()
            pass.colorAttachments[0].texture = drawable.texture
            pass.colorAttachments[0].loadAction = .clear
            pass.colorAttachments[0].storeAction = .store
            pass.colorAttachments[0].clearColor = MTLClearColorMake(0, 0, 0, 1)

            guard let cmd = queue.makeCommandBuffer(),
                  let enc = cmd.makeRenderCommandEncoder(descriptor: pass) else { return }
            enc.setRenderPipelineState(pipeline)
            var s = scale
            enc.setVertexBytes(&s, length: MemoryLayout<SIMD2<Float>>.stride, index: 0)
            enc.setFragmentTexture(texture, index: 0)
            enc.setFragmentSamplerState(sampler, index: 0)
            enc.drawPrimitives(type: .triangleStrip, vertexStart: 0, vertexCount: 4)
            enc.endEncoding()
            cmd.present(drawable)
            cmd.commit()
        }
    }
}
