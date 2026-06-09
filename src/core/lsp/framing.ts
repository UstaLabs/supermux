// LSP base-protocol framing: `Content-Length: N\r\n\r\n<json>`.
//
// The broker is a dumb pipe between the web client (which speaks raw JSON-RPC
// strings over the WebSocket) and the language server (which speaks
// Content-Length-framed JSON-RPC over stdio). These helpers do the framing
// transcode; they never parse LSP semantics.

export function encodeMessage(json: string): Buffer {
  const body = Buffer.from(json, "utf8")
  const header = Buffer.from(`Content-Length: ${body.length}\r\n\r\n`, "ascii")
  return Buffer.concat([header, body])
}

const HEADER_SEP = Buffer.from("\r\n\r\n", "ascii")

// Streaming reader: feed it stdout chunks, get back complete JSON message
// bodies. Handles headers/bodies split across chunks and multiple messages in
// one chunk. Body length is in BYTES (utf8), so we slice the Buffer by byte
// offset before decoding.
export class MessageReader {
  private buf: Buffer = Buffer.alloc(0)

  constructor(private readonly onMessage: (json: string) => void) {}

  push(chunk: Buffer): void {
    this.buf = this.buf.length ? Buffer.concat([this.buf, chunk]) : chunk
    for (;;) {
      const headerEnd = this.buf.indexOf(HEADER_SEP)
      if (headerEnd === -1) return // header not complete yet
      const header = this.buf.subarray(0, headerEnd).toString("ascii")
      const m = /content-length:\s*(\d+)/i.exec(header)
      if (!m) {
        // Malformed header — drop it and resync past the separator.
        this.buf = this.buf.subarray(headerEnd + HEADER_SEP.length)
        continue
      }
      const len = Number(m[1])
      const bodyStart = headerEnd + HEADER_SEP.length
      if (this.buf.length < bodyStart + len) return // body not fully arrived
      const body = this.buf.subarray(bodyStart, bodyStart + len).toString("utf8")
      this.buf = this.buf.subarray(bodyStart + len)
      this.onMessage(body)
    }
  }
}
