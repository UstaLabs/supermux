export function encodeFrame(value: unknown): Buffer {
  const json = Buffer.from(JSON.stringify(value), "utf8")
  const header = Buffer.alloc(4)
  header.writeUInt32BE(json.length, 0)
  return Buffer.concat([header, json])
}

export function decodeFrames(buf: Buffer): { messages: unknown[]; rest: Buffer } {
  const messages: unknown[] = []
  let offset = 0
  while (offset + 4 <= buf.length) {
    const len = buf.readUInt32BE(offset)
    if (offset + 4 + len > buf.length) break
    const json = buf.subarray(offset + 4, offset + 4 + len).toString("utf8")
    messages.push(JSON.parse(json))
    offset += 4 + len
  }
  return { messages, rest: buf.subarray(offset) }
}
