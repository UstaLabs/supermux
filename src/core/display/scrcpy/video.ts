export interface AccessUnit { config: boolean; keyFrame: boolean; data: Uint8Array }

const HEADER = 12
const FLAG_CONFIG = 0x80000000
const FLAG_KEY = 0x40000000

// Parses scrcpy's video frame stream (after handshake/codec-meta are stripped):
// repeating [pts_flags u64 BE | size u32 BE | payload]. See protocol.md.
export class ScrcpyVideoParser {
  private buf = new Uint8Array(0)
  onAccessUnit: ((au: AccessUnit) => void) | null = null

  push(chunk: Uint8Array): void {
    const merged = new Uint8Array(this.buf.length + chunk.length)
    merged.set(this.buf)
    merged.set(chunk, this.buf.length)
    this.buf = merged
    this.drain()
  }

  private drain(): void {
    while (this.buf.length >= HEADER) {
      const dv = new DataView(this.buf.buffer, this.buf.byteOffset, this.buf.byteLength)
      const hi = dv.getUint32(0)
      const size = dv.getUint32(8)
      if (this.buf.length < HEADER + size) return
      const data = this.buf.slice(HEADER, HEADER + size)
      this.onAccessUnit?.({
        config: (hi & FLAG_CONFIG) !== 0,
        keyFrame: (hi & FLAG_KEY) !== 0,
        data,
      })
      this.buf = this.buf.slice(HEADER + size)
    }
  }
}
