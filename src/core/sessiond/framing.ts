import { decodeFrames } from "../../shared/frame-codec"

export const SESSIOND_MAX_FRAME_BYTES = 8 * 1024 * 1024
export const SESSIOND_MAX_BUFFER_BYTES = SESSIOND_MAX_FRAME_BYTES + 4

export class SessiondFrameError extends Error {
  constructor(
    message: string,
    readonly reason: "oversized" | "malformed",
  ) {
    super(message)
    this.name = "SessiondFrameError"
  }
}

/** Incremental framed-JSON parser retaining at most one incomplete frame. */
export class SessiondFrameAccumulator {
  private readonly header = Buffer.allocUnsafe(4)
  private headerBytes = 0
  private frame?: Buffer
  private payloadLength = 0
  private payloadBytes = 0

  get residualBytes(): number {
    return this.frame ? 4 + this.payloadBytes : this.headerBytes
  }

  push(input: Buffer | Uint8Array): unknown[] {
    const bytes = Buffer.isBuffer(input)
      ? input
      : Buffer.from(input.buffer, input.byteOffset, input.byteLength)
    const messages: unknown[] = []
    let cursor = 0
    try {
      while (cursor < bytes.length) {
        if (!this.frame) {
          const headerRemaining = 4 - this.headerBytes
          const copied = Math.min(headerRemaining, bytes.length - cursor)
          bytes.copy(this.header, this.headerBytes, cursor, cursor + copied)
          this.headerBytes += copied
          cursor += copied
          if (this.headerBytes < 4) break

          this.payloadLength = this.header.readUInt32BE(0)
          if (this.payloadLength > SESSIOND_MAX_FRAME_BYTES) {
            throw new SessiondFrameError("sessiond frame too large", "oversized")
          }
          this.frame = Buffer.allocUnsafe(4 + this.payloadLength)
          this.header.copy(this.frame, 0)
          this.headerBytes = 0
          this.payloadBytes = 0
          if (this.payloadLength === 0) messages.push(this.finishFrame())
        }

        if (this.frame) {
          const payloadRemaining = this.payloadLength - this.payloadBytes
          const copied = Math.min(payloadRemaining, bytes.length - cursor)
          bytes.copy(this.frame, 4 + this.payloadBytes, cursor, cursor + copied)
          this.payloadBytes += copied
          cursor += copied
          if (this.payloadBytes === this.payloadLength) messages.push(this.finishFrame())
        }
      }
      return messages
    } catch (error) {
      this.reset()
      if (error instanceof SessiondFrameError) throw error
      throw new SessiondFrameError("malformed sessiond frame", "malformed")
    }
  }

  private finishFrame(): unknown {
    const frame = this.frame!
    this.frame = undefined
    this.payloadLength = 0
    this.payloadBytes = 0
    const decoded = decodeFrames(frame)
    if (decoded.messages.length !== 1 || decoded.rest.length !== 0) {
      throw new SessiondFrameError("malformed sessiond frame", "malformed")
    }
    return decoded.messages[0]
  }

  private reset(): void {
    this.headerBytes = 0
    this.frame = undefined
    this.payloadLength = 0
    this.payloadBytes = 0
  }
}
