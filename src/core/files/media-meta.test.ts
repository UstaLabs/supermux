import { describe, expect, test } from "bun:test"
import { readFileSync } from "fs"
import { join } from "path"
import { probeFileMeta, probeMediaMeta, type ReadAt } from "./media-meta"

// Real files from real encoders (Pillow, x264, libvpx/VP9, exiftool-checked), each 64×48 stored:
// tests/fixtures/media/. A wrong width/height swap, a missed EXIF turn or a misread box shows up.
const FIX = join(import.meta.dir, "../../../tests/fixtures/media")
const fixture = (name: string) => join(FIX, name)

/** A ReadAt over a fixture that remembers every range it was asked for. */
function recordingReader(name: string): { read: ReadAt; size: number; reads: Array<[number, number]> } {
  const bytes = new Uint8Array(readFileSync(fixture(name)))
  const reads: Array<[number, number]> = []
  const read: ReadAt = async (offset, length) => {
    reads.push([offset, length])
    return bytes.subarray(offset, Math.min(bytes.length, offset + length))
  }
  return { read, size: bytes.length, reads }
}

const near = (actual: number | undefined, expected: number, within = 20) => {
  expect(actual).toBeDefined()
  expect(Math.abs((actual ?? 0) - expected)).toBeLessThanOrEqual(within)
}

describe("pictures", () => {
  test("PNG: the IHDR size", async () => {
    expect(await probeFileMeta(fixture("png-64x48.png"))).toEqual({ width: 64, height: 48 })
  })

  test("GIF: the logical screen size", async () => {
    expect(await probeFileMeta(fixture("gif-64x48.gif"))).toEqual({ width: 64, height: 48 })
  })

  test("WebP: lossy (VP8), lossless (VP8L) and extended (VP8X)", async () => {
    expect(await probeFileMeta(fixture("webp-lossy-64x48.webp"))).toEqual({ width: 64, height: 48 })
    expect(await probeFileMeta(fixture("webp-lossless-64x48.webp"))).toEqual({ width: 64, height: 48 })
    expect(await probeFileMeta(fixture("webp-alpha-64x48.webp"))).toEqual({ width: 64, height: 48 })
  })

  test("JPEG: baseline and progressive frame headers", async () => {
    expect(await probeFileMeta(fixture("jpeg-64x48.jpg"))).toEqual({ width: 64, height: 48 })
    expect(await probeFileMeta(fixture("jpeg-progressive-64x48.jpg"))).toEqual({ width: 64, height: 48 })
  })

  test("JPEG: EXIF orientation 6 (a phone held upright) reports the DISPLAYED size", async () => {
    expect(await probeFileMeta(fixture("jpeg-exif-orientation6-64x48.jpg"))).toEqual({ width: 48, height: 64 })
  })

  test("JPEG: the frame header 190 KB in, behind ICC and comment segments, costs only segment headers", async () => {
    const r = recordingReader("jpeg-big-segments-64x48.jpg")
    expect(await probeMediaMeta(r.read, r.size)).toEqual({ width: 64, height: 48 })
    const total = r.reads.reduce((sum, [, len]) => sum + len, 0)
    expect(total).toBeLessThan(2048)
  })
})

describe("clips", () => {
  test("MP4 with moov first (faststart): size and length", async () => {
    const meta = await probeFileMeta(fixture("mp4-faststart-64x48.mp4"))
    expect(meta?.width).toBe(64)
    expect(meta?.height).toBe(48)
    near(meta?.duration_ms, 1500)
  })

  test("MP4 with moov AFTER the media data: found by walking box sizes, the mdat never read", async () => {
    const r = recordingReader("mp4-moov-at-end-64x48.mp4")
    const meta = await probeMediaMeta(r.read, r.size)
    expect(meta?.width).toBe(64)
    expect(meta?.height).toBe(48)
    near(meta?.duration_ms, 1500)
    // Top level: ftyp@0 free@32 mdat@40 (12026 bytes) moov@12066. Inside mdat only its box header
    // may be read — never the media data behind it.
    const mdatData: [number, number] = [40 + 16, 12066]
    const inside = r.reads.reduce((sum, [o, l]) => sum + Math.max(0, Math.min(o + l, mdatData[1]) - Math.max(o, mdatData[0])), 0)
    expect(inside).toBe(0)
  })

  test("MP4 recorded turned a quarter (display matrix 90°) reports the DISPLAYED size", async () => {
    const meta = await probeFileMeta(fixture("mp4-rotated90-64x48.mp4"))
    expect(meta?.width).toBe(48)
    expect(meta?.height).toBe(64)
    near(meta?.duration_ms, 1500)
  })

  test("QuickTime .mov: the same boxes", async () => {
    const meta = await probeFileMeta(fixture("mov-64x48.mov"))
    expect(meta?.width).toBe(64)
    expect(meta?.height).toBe(48)
    near(meta?.duration_ms, 2500)
  })

  test("WebM (VP9): Info Duration × TimecodeScale and the video track size", async () => {
    const meta = await probeFileMeta(fixture("webm-vp9-64x48.webm"))
    expect(meta?.width).toBe(64)
    expect(meta?.height).toBe(48)
    near(meta?.duration_ms, 1508)
  })

  test("Matroska (.mkv): the same parser", async () => {
    const meta = await probeFileMeta(fixture("mkv-64x48.mkv"))
    expect(meta?.width).toBe(64)
    expect(meta?.height).toBe(48)
    near(meta?.duration_ms, 1500)
  })

  test("a live WebM with no Duration (a browser's MediaRecorder) still has its size, and no made-up length", async () => {
    const meta = await probeFileMeta(fixture("webm-live-no-duration-64x48.webm"))
    expect(meta).toEqual({ width: 64, height: 48 })
  })
})

describe("anything else", () => {
  test("a file that is not media is unknown", async () => {
    const r: ReadAt = async (offset, length) => new TextEncoder().encode("%PDF-1.7 not a picture at all, just text".slice(offset, offset + length))
    expect(await probeMediaMeta(r, 40)).toBeNull()
  })

  test("a truncated header never throws and never reports what it did not read", async () => {
    const whole = new Uint8Array(readFileSync(fixture("mp4-faststart-64x48.mp4")))
    const cut = whole.subarray(0, 200) // ftyp + the first bytes of moov: mvhd, but no track
    const read: ReadAt = async (o, l) => cut.subarray(o, Math.min(cut.length, o + l))
    const meta = await probeMediaMeta(read, whole.length)
    expect(meta?.width).toBeUndefined()
    near(meta?.duration_ms, 1500) // what it did read is still true
    const jpeg = new Uint8Array(readFileSync(fixture("jpeg-64x48.jpg"))).subarray(0, 30)
    expect(await probeMediaMeta(async (o, l) => jpeg.subarray(o, o + l), 1155)).toBeNull()
  })
})
