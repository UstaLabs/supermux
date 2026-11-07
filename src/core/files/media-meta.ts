// src/core/files/media-meta.ts
//
// A picture's or a clip's shape and length, read from the container header alone — pure TS, no
// ffmpeg and no other dependency (issue #18: a hidden external binary breaks the hosts without it).
// The client uses these to reserve the right box before anything loads, to lay a collage out by
// real aspect, and to show a clip's length without fetching the clip.
//
// Only headers are read, through a ranged reader: a JPEG's frame header behind its EXIF, an MP4's
// `moov` box wherever it sits (often after the whole `mdat`), a Matroska/WebM file's Info and
// Tracks before the first Cluster.
import { open } from "fs/promises"

/** What the header says. Width/height are the DISPLAYED size (EXIF orientation and a clip's rotation applied). */
export interface MediaMeta {
  width?: number
  height?: number
  duration_ms?: number
}

/** `length` bytes at `offset` — fewer at the end of the file. */
export type ReadAt = (offset: number, length: number) => Promise<Uint8Array>

/** Read [ReadAt] over a file on disk, for one probe. */
export async function probeFileMeta(path: string): Promise<MediaMeta | null> {
  const handle = await open(path, "r")
  try {
    const size = (await handle.stat()).size
    const read: ReadAt = async (offset, length) => {
      const len = Math.max(0, Math.min(length, size - offset))
      const buf = new Uint8Array(len)
      if (len === 0) return buf
      const { bytesRead } = await handle.read(buf, 0, len, offset)
      return bytesRead === len ? buf : buf.subarray(0, bytesRead)
    }
    return await probeMediaMeta(read, size)
  } finally {
    await handle.close()
  }
}

/** The shape (and a clip's length) of a file, found by its magic bytes, not its claimed mime. */
export async function probeMediaMeta(read: ReadAt, size: number): Promise<MediaMeta | null> {
  if (size < 12) return null
  // Enough for every magic below (WebP's VP8X canvas size ends at byte 30).
  const head = await read(0, Math.min(size, 32))
  try {
    if (isPng(head)) return pngSize(head)
    if (isGif(head)) return gifSize(head)
    if (isWebp(head)) return webpSize(head)
    if (head[0] === 0xff && head[1] === 0xd8) return await jpegSize(read, size)
    if (u32be(head, 0) === EBML_ID) return await matroskaMeta(read, size)
    if (isIsoBmff(head)) return await mp4Meta(read, size)
  } catch {
    // A truncated or hostile header is "unknown", never an error the caller must handle.
  }
  return null
}

// ── Pictures ──────────────────────────────────────────────────────────────────────────────────────

const PNG_SIG = [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]

function isPng(h: Uint8Array): boolean {
  return h.length >= 24 && PNG_SIG.every((b, i) => h[i] === b) && ascii(h, 12, 4) === "IHDR"
}

function pngSize(h: Uint8Array): MediaMeta | null {
  return dims(u32be(h, 16), u32be(h, 20))
}

function isGif(h: Uint8Array): boolean {
  const sig = ascii(h, 0, 6)
  return sig === "GIF87a" || sig === "GIF89a"
}

function gifSize(h: Uint8Array): MediaMeta | null {
  return dims(u16le(h, 6), u16le(h, 8))
}

function isWebp(h: Uint8Array): boolean {
  return h.length >= 30 && ascii(h, 0, 4) === "RIFF" && ascii(h, 8, 4) === "WEBP"
}

function webpSize(h: Uint8Array): MediaMeta | null {
  const chunk = ascii(h, 12, 4)
  const d = 20 // the first chunk's payload
  if (chunk === "VP8 ") {
    // Lossy: a 3-byte frame tag, the 9d 01 2a start code, then 14-bit width and height.
    if (h[d + 3] !== 0x9d || h[d + 4] !== 0x01 || h[d + 5] !== 0x2a) return null
    return dims(u16le(h, d + 6) & 0x3fff, u16le(h, d + 8) & 0x3fff)
  }
  if (chunk === "VP8L") {
    // Lossless: the 0x2f signature, then width-1 and height-1 in 14 bits each.
    if (h[d] !== 0x2f) return null
    const bits = u32le(h, d + 1)
    return dims((bits & 0x3fff) + 1, ((bits >>> 14) & 0x3fff) + 1)
  }
  if (chunk === "VP8X") {
    // Extended (alpha, animation, metadata): the canvas size, 24-bit width-1 and height-1.
    return dims(u24le(h, d + 4) + 1, u24le(h, d + 7) + 1)
  }
  return null
}

/** How far into a JPEG the frame header is looked for: past any EXIF, ICC and comment segments. */
const JPEG_SCAN_LIMIT = 16 * 1024 * 1024

/**
 * Walk the JPEG's segments to its frame header (SOFn), reading 4 bytes per segment, plus the EXIF
 * segment's orientation on the way: 5–8 turn the picture a quarter, so the displayed size swaps.
 */
async function jpegSize(read: ReadAt, size: number): Promise<MediaMeta | null> {
  let pos = 2
  let orientation = 1
  while (pos + 4 <= Math.min(size, JPEG_SCAN_LIMIT)) {
    const seg = await read(pos, 4)
    if (seg[0] !== 0xff) return null
    const marker = seg[1]!
    if (marker === 0xff) {
      pos += 1 // fill byte
      continue
    }
    if (marker === 0xd8 || marker === 0x01 || (marker >= 0xd0 && marker <= 0xd7)) {
      pos += 2 // standalone marker, no length
      continue
    }
    if (marker === 0xd9 || marker === 0xda) return null // end of image / start of scan: no frame header
    const length = u16be(seg, 2)
    if (length < 2) return null
    if (marker === 0xe1 && orientation === 1) {
      orientation = exifOrientation(await read(pos + 4, Math.min(length - 2, 64 * 1024))) ?? 1
    }
    if (isSof(marker)) {
      const sof = await read(pos + 4, 5)
      const height = u16be(sof, 1)
      const width = u16be(sof, 3)
      return orientation >= 5 && orientation <= 8 ? dims(height, width) : dims(width, height)
    }
    pos += 2 + length
  }
  return null
}

function isSof(m: number): boolean {
  return m >= 0xc0 && m <= 0xcf && m !== 0xc4 && m !== 0xc8 && m !== 0xcc
}

/** The orientation tag (0x0112) of an APP1 EXIF payload's first IFD, or null. */
function exifOrientation(p: Uint8Array): number | null {
  if (ascii(p, 0, 6) !== "Exif\0\0") return null
  const t = 6 // TIFF header
  const order = ascii(p, t, 2)
  if (order !== "II" && order !== "MM") return null
  const le = order === "II"
  const r16 = (o: number) => (le ? u16le(p, t + o) : u16be(p, t + o))
  const r32 = (o: number) => (le ? u32le(p, t + o) : u32be(p, t + o))
  if (r16(2) !== 42) return null
  const ifd = r32(4)
  const count = r16(ifd)
  for (let i = 0; i < count; i++) {
    const entry = ifd + 2 + i * 12
    if (t + entry + 12 > p.length) return null
    if (r16(entry) === 0x0112) return r16(entry + 8)
  }
  return null
}

// ── MP4 / QuickTime (ISO base media) ─────────────────────────────────────────────────────────────

const ISO_TOP_LEVEL = new Set(["ftyp", "moov", "mdat", "free", "skip", "wide", "pnot", "uuid"])

function isIsoBmff(h: Uint8Array): boolean {
  return ISO_TOP_LEVEL.has(ascii(h, 4, 4))
}

/** A `moov` this big is not a header; refuse rather than read it into memory. */
const MOOV_LIMIT = 32 * 1024 * 1024

/**
 * Find `moov` by walking the top-level boxes by their sizes alone — a 16-byte read each, so a
 * `moov` behind a large `mdat` (the default for most encoders and phones) costs two or three
 * reads, not the file. Then: the movie length from `mvhd` (or `mehd` for a fragmented file), and
 * the video track's displayed size from its `tkhd`, turned by its matrix.
 */
async function mp4Meta(read: ReadAt, size: number): Promise<MediaMeta | null> {
  let pos = 0
  for (let boxes = 0; pos + 8 <= size && boxes < 4096; boxes++) {
    const h = await read(pos, 16)
    let boxSize = u32be(h, 0)
    let header = 8
    if (boxSize === 1) {
      boxSize = u64be(h, 8)
      header = 16
    } else if (boxSize === 0) {
      boxSize = size - pos
    }
    if (boxSize < header) return null
    if (ascii(h, 4, 4) === "moov") {
      const length = Math.min(boxSize, size - pos) - header
      if (length > MOOV_LIMIT) return null
      return moovMeta(await read(pos + header, length))
    }
    pos += boxSize
  }
  return null
}

interface Box {
  type: string
  start: number // payload start, in the buffer
  end: number
}

function* boxes(b: Uint8Array, from: number, to: number): Generator<Box> {
  let pos = from
  while (pos + 8 <= to) {
    let size = u32be(b, pos)
    let header = 8
    if (size === 1) {
      size = u64be(b, pos + 8)
      header = 16
    } else if (size === 0) {
      size = to - pos
    }
    if (size < header || pos + size > to) return
    yield { type: ascii(b, pos + 4, 4), start: pos + header, end: pos + size }
    pos += size
  }
}

function child(b: Uint8Array, parent: Box, type: string): Box | undefined {
  for (const c of boxes(b, parent.start, parent.end)) if (c.type === type) return c
  return undefined
}

function moovMeta(b: Uint8Array): MediaMeta | null {
  const moov: Box = { type: "moov", start: 0, end: b.length }
  let durationMs: number | undefined
  let timescale = 0
  const mvhd = child(b, moov, "mvhd")
  if (mvhd) {
    const v1 = b[mvhd.start] === 1
    timescale = u32be(b, mvhd.start + (v1 ? 20 : 12))
    const duration = v1 ? u64be(b, mvhd.start + 24) : u32be(b, mvhd.start + 16)
    if (timescale > 0 && duration > 0 && duration !== 0xffffffff) durationMs = Math.round((duration * 1000) / timescale)
  }
  if (durationMs === undefined && timescale > 0) {
    // A fragmented file can carry its length in mvex/mehd instead.
    const mehd = (() => {
      const mvex = child(b, moov, "mvex")
      return mvex ? child(b, mvex, "mehd") : undefined
    })()
    if (mehd) {
      const v1 = b[mehd.start] === 1
      const d = v1 ? u64be(b, mehd.start + 4) : u32be(b, mehd.start + 4)
      if (d > 0) durationMs = Math.round((d * 1000) / timescale)
    }
  }
  let size: { width: number; height: number } | null = null
  for (const trak of boxes(b, moov.start, moov.end)) {
    if (trak.type !== "trak") continue
    const tkhd = child(b, trak, "tkhd")
    if (!tkhd) continue
    const video = handlerType(b, trak) === "vide"
    const s = tkhdSize(b, tkhd)
    if (s && (video || size === null)) size = s
    if (s && video) break
  }
  if (!size && durationMs === undefined) return null
  return { ...(size ?? {}), ...(durationMs !== undefined ? { duration_ms: durationMs } : {}) }
}

function handlerType(b: Uint8Array, trak: Box): string | null {
  const mdia = child(b, trak, "mdia")
  const hdlr = mdia ? child(b, mdia, "hdlr") : undefined
  return hdlr ? ascii(b, hdlr.start + 8, 4) : null
}

/** A track's size in 16.16 fixed point, swapped when its display matrix turns it a quarter. */
function tkhdSize(b: Uint8Array, tkhd: Box): { width: number; height: number } | null {
  const v1 = b[tkhd.start] === 1
  const matrix = tkhd.start + (v1 ? 52 : 40)
  const width = Math.round(u32be(b, matrix + 36) / 65536)
  const height = Math.round(u32be(b, matrix + 40) / 65536)
  if (width <= 0 || height <= 0) return null
  const a = i32be(b, matrix)
  const d = i32be(b, matrix + 16)
  const quarterTurn = a === 0 && d === 0
  return quarterTurn ? { width: height, height: width } : { width, height }
}

// ── Matroska / WebM (EBML) ───────────────────────────────────────────────────────────────────────

const EBML_ID = 0x1a45dfa3
const SEGMENT = 0x18538067
const INFO = 0x1549a966
const TIMECODE_SCALE = 0x2ad7b1
const DURATION = 0x4489
const TRACKS = 0x1654ae6b
const TRACK_ENTRY = 0xae
const TRACK_TYPE = 0x83
const VIDEO = 0xe0
const PIXEL_WIDTH = 0xb0
const PIXEL_HEIGHT = 0xba
const DISPLAY_WIDTH = 0x54b0
const DISPLAY_HEIGHT = 0x54ba
const CLUSTER = 0x1f43b675

/** Info and Tracks bigger than this are not headers. */
const EBML_ELEMENT_LIMIT = 4 * 1024 * 1024

/**
 * Walk the Segment's children by their sizes to Info (TimecodeScale + Duration) and Tracks (the
 * video track's size), stopping at the first Cluster — the media data. A live recording (a
 * browser's MediaRecorder) writes no Duration: the length is then unknown, the size still found.
 */
async function matroskaMeta(read: ReadAt, size: number): Promise<MediaMeta | null> {
  const ebml = ebmlHeader(await read(0, 16), 0)
  if (!ebml || ebml.id !== EBML_ID || ebml.size === null) return null
  const segPos = ebml.dataStart + ebml.size
  const seg = ebmlHeader(await read(segPos, 16), 0)
  if (!seg || seg.id !== SEGMENT) return null
  const segEnd = seg.size === null ? size : Math.min(size, segPos + seg.dataStart + seg.size)
  let pos = segPos + seg.dataStart
  let scale = 1_000_000
  let duration: number | undefined
  let dims_: { width: number; height: number } | null = null
  for (let n = 0; pos < segEnd && n < 4096; n++) {
    const el = ebmlHeader(await read(pos, 16), 0)
    if (!el || el.size === null) break
    if (el.id === CLUSTER) break
    if ((el.id === INFO || el.id === TRACKS) && el.size <= EBML_ELEMENT_LIMIT) {
      const body = await read(pos + el.dataStart, el.size)
      if (el.id === INFO) {
        for (const c of ebmlChildren(body, 0, body.length)) {
          if (c.id === TIMECODE_SCALE) scale = uint(body, c.start, c.end) || scale
          if (c.id === DURATION) duration = ebmlFloat(body, c.start, c.end)
        }
      } else {
        dims_ = videoTrackSize(body) ?? dims_
      }
    }
    pos += el.dataStart + el.size
  }
  const durationMs = duration !== undefined && duration > 0 ? Math.round((duration * scale) / 1_000_000) : undefined
  if (!dims_ && durationMs === undefined) return null
  return { ...(dims_ ?? {}), ...(durationMs !== undefined ? { duration_ms: durationMs } : {}) }
}

function videoTrackSize(tracks: Uint8Array): { width: number; height: number } | null {
  for (const entry of ebmlChildren(tracks, 0, tracks.length)) {
    if (entry.id !== TRACK_ENTRY) continue
    let type = 0
    let px: number[] = []
    let shown: number[] = []
    for (const c of ebmlChildren(tracks, entry.start, entry.end)) {
      if (c.id === TRACK_TYPE) type = uint(tracks, c.start, c.end)
      if (c.id === VIDEO) {
        for (const v of ebmlChildren(tracks, c.start, c.end)) {
          if (v.id === PIXEL_WIDTH) px[0] = uint(tracks, v.start, v.end)
          if (v.id === PIXEL_HEIGHT) px[1] = uint(tracks, v.start, v.end)
          if (v.id === DISPLAY_WIDTH) shown[0] = uint(tracks, v.start, v.end)
          if (v.id === DISPLAY_HEIGHT) shown[1] = uint(tracks, v.start, v.end)
        }
      }
    }
    if (type !== 1) continue
    // The display size when the file gives one (anamorphic video), else the coded size.
    const [w, h] = shown[0] && shown[1] ? shown : px
    if (w && h) return { width: w, height: h }
  }
  return null
}

interface EbmlEl {
  id: number
  size: number | null // null = unknown (a live stream's Segment)
  dataStart: number // offset of the payload from the element's first byte
}

/** An element's ID (marker bits kept) and size (marker removed) at [at]. */
function ebmlHeader(b: Uint8Array, at: number): EbmlEl | null {
  const idLen = vintLength(b[at])
  if (idLen === 0 || idLen > 4 || at + idLen > b.length) return null
  let id = 0
  for (let i = 0; i < idLen; i++) id = id * 256 + b[at + i]!
  const sizeLen = vintLength(b[at + idLen])
  if (sizeLen === 0 || at + idLen + sizeLen > b.length) return null
  let size = b[at + idLen]! & (0xff >> sizeLen)
  let allOnes = size === 0xff >> sizeLen
  for (let i = 1; i < sizeLen; i++) {
    const byte = b[at + idLen + i]!
    if (byte !== 0xff) allOnes = false
    size = size * 256 + byte
  }
  return { id, size: allOnes ? null : size, dataStart: idLen + sizeLen }
}

function vintLength(first: number | undefined): number {
  if (first === undefined || first === 0) return 0
  let len = 1
  for (let mask = 0x80; (first & mask) === 0; mask >>= 1) len++
  return len
}

function* ebmlChildren(b: Uint8Array, from: number, to: number): Generator<{ id: number; start: number; end: number }> {
  let pos = from
  while (pos < to) {
    const el = ebmlHeader(b, pos)
    if (!el || el.size === null) return
    const start = pos + el.dataStart
    const end = start + el.size
    if (end > to) return
    yield { id: el.id, start, end }
    pos = end
  }
}

function uint(b: Uint8Array, start: number, end: number): number {
  let v = 0
  for (let i = start; i < end; i++) v = v * 256 + b[i]!
  return v
}

function ebmlFloat(b: Uint8Array, start: number, end: number): number | undefined {
  const view = new DataView(b.buffer, b.byteOffset + start, end - start)
  if (end - start === 4) return view.getFloat32(0)
  if (end - start === 8) return view.getFloat64(0)
  return undefined
}

// ── Bytes ────────────────────────────────────────────────────────────────────────────────────────

function dims(width: number, height: number): MediaMeta | null {
  return width > 0 && height > 0 ? { width, height } : null
}

function ascii(b: Uint8Array, at: number, len: number): string {
  let s = ""
  for (let i = 0; i < len && at + i < b.length; i++) s += String.fromCharCode(b[at + i]!)
  return s
}

function u16be(b: Uint8Array, at: number): number {
  return (b[at]! << 8) | b[at + 1]!
}

function u16le(b: Uint8Array, at: number): number {
  return b[at]! | (b[at + 1]! << 8)
}

function u24le(b: Uint8Array, at: number): number {
  return b[at]! | (b[at + 1]! << 8) | (b[at + 2]! << 16)
}

function u32be(b: Uint8Array, at: number): number {
  return ((b[at]! << 24) >>> 0) + ((b[at + 1]! << 16) | (b[at + 2]! << 8) | b[at + 3]!)
}

function u32le(b: Uint8Array, at: number): number {
  return ((b[at + 3]! << 24) >>> 0) + ((b[at + 2]! << 16) | (b[at + 1]! << 8) | b[at]!)
}

function i32be(b: Uint8Array, at: number): number {
  return (b[at]! << 24) | (b[at + 1]! << 16) | (b[at + 2]! << 8) | b[at + 3]!
}

function u64be(b: Uint8Array, at: number): number {
  return u32be(b, at) * 2 ** 32 + u32be(b, at + 4)
}
