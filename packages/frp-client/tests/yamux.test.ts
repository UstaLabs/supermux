import { afterEach, expect, test } from 'bun:test'
import { createHash, randomBytes } from 'node:crypto'
import { createServer, connect, type Server, type Socket } from 'node:net'
import { INITIAL_WINDOW, YamuxSession, type YamuxStream } from '../src/yamux.js'

const cleanup: Array<() => void> = []
afterEach(() => { for (const f of cleanup.splice(0)) f() })

const options = { keepAliveMs: 0, timeoutMs: 1000, maxStreamWindow: 6 * 1024 * 1024 }

/** A client and a server session over a real TCP connection. */
async function pair(over: Partial<typeof options> = {}) {
  let accepted!: (socket: Socket) => void
  const serverSocket = new Promise<Socket>(r => { accepted = r })
  const server: Server = createServer(accepted)
  await new Promise<void>(r => server.listen(0, '127.0.0.1', r))
  const port = (server.address() as { port: number }).port
  const clientSocket = connect(port, '127.0.0.1')
  await new Promise(r => clientSocket.once('connect', r))
  const client = new YamuxSession(clientSocket, { client: true, ...options, ...over })
  const srv = new YamuxSession(await serverSocket, { client: false, ...options, ...over })
  cleanup.push(() => { client.close(); srv.close(); server.close() })
  const incoming: YamuxStream[] = []
  const waiters: Array<(s: YamuxStream) => void> = []
  srv.onStream = s => { const w = waiters.shift(); if (w) w(s); else incoming.push(s) }
  const accept = () => incoming.length ? Promise.resolve(incoming.shift()!) : new Promise<YamuxStream>(r => waiters.push(r))
  return { client, srv, accept, clientSocket }
}

function collect(stream: YamuxStream): Promise<Buffer> {
  const chunks: Buffer[] = []
  stream.on('data', (c: Buffer) => chunks.push(c))
  return new Promise((resolve, reject) => {
    stream.once('end', () => resolve(Buffer.concat(chunks)))
    stream.once('error', reject)
  })
}

const sha = (b: Buffer) => createHash('sha256').update(b).digest('hex')

test('a stream carries data both ways and half-closes with FIN', async () => {
  const { client, accept } = await pair()
  const a = client.open()
  const b = await accept()
  const atB = collect(b)
  a.end('hello from client')
  expect((await atB).toString()).toBe('hello from client')
  const atA = collect(a)
  b.end('hello from server')
  expect((await atA).toString()).toBe('hello from server')
})

test('a transfer far larger than the window arrives intact, in both directions at once', async () => {
  const { client, accept } = await pair()
  const up = randomBytes(20 * 1024 * 1024)
  const down = randomBytes(20 * 1024 * 1024)
  const a = client.open()
  const b = await accept()
  const atB = collect(b)
  const atA = collect(a)
  a.end(up)
  b.end(down)
  expect(sha(await atB)).toBe(sha(up))
  expect(sha(await atA)).toBe(sha(down))
})

test('a reader that does not read stops the writer at the window instead of overflowing it', async () => {
  const { client, accept } = await pair({ maxStreamWindow: INITIAL_WINDOW })
  const a = client.open()
  const b = await accept()
  b.pause()
  let written = 0
  const chunk = Buffer.alloc(64 * 1024)
  const writes = (async () => { for (;;) { await new Promise<void>((r, j) => a.write(chunk, e => e ? j(e) : r())); written += chunk.length } })()
  writes.catch(() => {})
  await new Promise(r => setTimeout(r, 300))
  // The window, plus what the writable side buffers before calling back.
  expect(written).toBeLessThanOrEqual(INITIAL_WINDOW + 2 * chunk.length)
  expect(written).toBeGreaterThanOrEqual(INITIAL_WINDOW - chunk.length)
  const before = written
  b.resume()
  await new Promise(r => setTimeout(r, 300))
  expect(written).toBeGreaterThan(before)
  a.destroy()
})

test('many concurrent streams stay separate', async () => {
  const { client, accept } = await pair()
  const sent = Array.from({ length: 50 }, (_, i) => randomBytes(10_000 + i))
  const streams = sent.map(() => client.open())
  const received = Promise.all(sent.map(async () => {
    const s = await accept()
    return collect(s).then(buf => ({ id: s.id, buf }))
  }))
  streams.forEach((s, i) => s.end(sent[i]))
  const byId = new Map((await received).map(r => [r.id, r.buf]))
  streams.forEach((s, i) => expect(sha(byId.get(s.id)!)).toBe(sha(sent[i]!)))
})

test('destroying a stream resets it on the other side', async () => {
  const { client, accept } = await pair()
  const a = client.open()
  a.write('x')
  const b = await accept()
  const error = new Promise<Error>(r => b.once('error', r))
  a.destroy()
  expect((await error).message).toContain('reset')
})

test('a peer that stops answering pings ends the session', async () => {
  const { client, clientSocket } = await pair({ keepAliveMs: 50, timeoutMs: 100 })
  const ended = new Promise<Error>(r => client.onClose(r))
  // Swallow everything the server would answer with.
  clientSocket.removeAllListeners('data')
  expect((await ended).message).toContain('keepalive')
})

test('closing the session ends every open stream', async () => {
  const { client } = await pair()
  const a = client.open()
  const closed = new Promise(r => a.once('close', r))
  client.close()
  await closed
  expect(client.closed).toBe(true)
  expect(() => client.open()).toThrow()
})
