/**
 * frp's v1 message protocol: a type byte, a big-endian int64 length, then that many bytes of JSON.
 * After login the control connection is encrypted both ways with AES-128-CFB (each side first sends its
 * random 16-byte IV), keyed by PBKDF2-HMAC-SHA1(token, "frp", 64 rounds, 16 bytes).
 */
import { createCipheriv, createDecipheriv, createHash, pbkdf2Sync, randomBytes } from 'node:crypto'
import { PassThrough, type Duplex, type Readable, type Writable } from 'node:stream'

export const MsgType = {
  Login: 0x6f, // 'o'
  LoginResp: 0x31, // '1'
  NewProxy: 0x70, // 'p'
  NewProxyResp: 0x32, // '2'
  CloseProxy: 0x63, // 'c'
  NewWorkConn: 0x77, // 'w'
  ReqWorkConn: 0x72, // 'r'
  StartWorkConn: 0x73, // 's'
  Ping: 0x68, // 'h'
  Pong: 0x34, // '4'
} as const

/** frp refuses a message longer than this; so do we. */
const MAX_MESSAGE = 10_240

export interface Message { type: number; body: Record<string, unknown> }

export function encodeMessage(type: number, body: object): Buffer {
  const json = Buffer.from(JSON.stringify(body))
  const head = Buffer.allocUnsafe(9)
  head.writeUInt8(type, 0)
  head.writeBigInt64BE(BigInt(json.length), 1)
  return Buffer.concat([head, json])
}

/** frp's auth key for a token: md5(token + timestamp), hex. */
export function privilegeKey(token: string, timestamp: number): string {
  return createHash('md5').update(`${token}${timestamp}`).digest('hex')
}

/** Resolves with exactly `n` bytes of `stream`, leaving the rest buffered in it. */
export function readExactly(stream: Readable, n: number): Promise<Buffer> {
  return new Promise((resolve, reject) => {
    const attempt = () => {
      const chunk = stream.read(n) as Buffer | null
      if (chunk !== null) { cleanup(); resolve(chunk); return true }
      return false
    }
    const onReadable = () => { attempt() }
    const onEnd = () => { cleanup(); reject(new Error('connection ended mid-message')) }
    const onError = (error: Error) => { cleanup(); reject(error) }
    const cleanup = () => {
      stream.off('readable', onReadable)
      stream.off('end', onEnd)
      stream.off('close', onEnd)
      stream.off('error', onError)
    }
    stream.on('readable', onReadable)
    stream.once('end', onEnd)
    stream.once('close', onEnd)
    stream.once('error', onError)
    attempt()
  })
}

export async function readMessage(stream: Readable): Promise<Message> {
  const head = await readExactly(stream, 9)
  const type = head.readUInt8(0)
  const length = head.readBigInt64BE(1)
  if (length < 0n || length > BigInt(MAX_MESSAGE)) throw new Error(`frp message length ${length} out of range`)
  const json = await readExactly(stream, Number(length))
  const body = JSON.parse(json.toString('utf8')) as unknown
  if (body === null || typeof body !== 'object' || Array.isArray(body)) throw new Error('frp message is not a JSON object')
  return { type, body: body as Record<string, unknown> }
}

export function cryptoKey(token: string): Buffer {
  return pbkdf2Sync(token, 'frp', 64, 16, 'sha1')
}

/** The encrypted control channel over `conn`: messages are written to `writer`, read from `reader`. */
export interface CryptoChannel { reader: Readable; write(type: number, body: object): void }

export function cryptoChannel(conn: Duplex, key: Buffer): CryptoChannel {
  const iv = randomBytes(16)
  const cipher = createCipheriv('aes-128-cfb', key, iv)
  conn.write(iv)
  const reader = new PassThrough()
  let decipher: ReturnType<typeof createDecipheriv> | undefined
  let pending: Buffer = Buffer.alloc(0)
  const onData = (chunk: Buffer) => {
    if (!decipher) {
      pending = Buffer.concat([pending, chunk])
      if (pending.length < 16) return
      decipher = createDecipheriv('aes-128-cfb', key, pending.subarray(0, 16))
      chunk = pending.subarray(16)
      pending = Buffer.alloc(0)
      if (chunk.length === 0) return
    }
    reader.write(decipher.update(chunk))
  }
  // Bytes the login left buffered in conn belong to this channel too.
  const leftover = conn.read() as Buffer | null
  if (leftover) onData(leftover)
  conn.on('data', onData)
  // readExactly left conn paused (a 'readable' listener stops flowing for good).
  conn.resume()
  conn.once('end', () => reader.end())
  conn.once('close', () => reader.end())
  conn.once('error', error => reader.destroy(error))
  return {
    reader,
    write(type: number, body: object) {
      (conn as Writable).write(cipher.update(encodeMessage(type, body)))
    },
  }
}
