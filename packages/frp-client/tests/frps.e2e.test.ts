/**
 * Against a real frps (FRPS_BIN, else skipped): TLS with a test CA, token auth, an http vhost, and a local
 * backend serving plain HTTP, a large body, SSE and a WebSocket.
 */
import { afterAll, beforeAll, describe, expect, test, setDefaultTimeout } from 'bun:test'
import { spawn, spawnSync, type ChildProcess } from 'node:child_process'
import { existsSync, mkdtempSync, readFileSync, writeFileSync } from 'node:fs'
import { connect, createServer } from 'node:net'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { FrpClient, FrpError, type FrpClientOptions } from '../src/index.js'

setDefaultTimeout(30_000)
const FRPS = process.env.FRPS_BIN ?? ''
const enabled = FRPS !== '' && existsSync(FRPS) && spawnSync('openssl', ['version']).status === 0

async function freePort(): Promise<number> {
  const server = createServer()
  await new Promise<void>(r => server.listen(0, '127.0.0.1', r))
  const port = (server.address() as { port: number }).port
  await new Promise(r => server.close(r))
  return port
}

function openssl(dir: string, ...args: string[]) {
  const r = spawnSync('openssl', args, { cwd: dir })
  if (r.status !== 0) throw new Error(`openssl ${args[0]}: ${r.stderr}`)
}

describe.skipIf(!enabled)('against frps', () => {
  const dir = mkdtempSync(join(tmpdir(), 'frps-e2e-'))
  const token = 'e2e-token'
  let frps: ChildProcess
  let bindPort = 0
  let vhostPort = 0
  let backendPort = 0
  let ca = ''
  let backend: ReturnType<typeof Bun.serve>
  const clients: FrpClient[] = []

  function options(over: Partial<FrpClientOptions> = {}, name = 'web', subdomain = 'app'): FrpClientOptions {
    return {
      serverAddr: '127.0.0.1',
      serverPort: bindPort,
      tls: { serverName: 'frps.test', ca },
      token,
      proxies: [{ name, type: 'http', subdomain, localIP: '127.0.0.1', localPort: backendPort }],
      ...over,
    }
  }

  async function started(over: Partial<FrpClientOptions> = {}, name?: string, subdomain?: string) {
    const client = new FrpClient(options(over, name, subdomain))
    clients.push(client)
    await client.start()
    return client
  }

  const via = (path: string, init: RequestInit = {}, subdomain = 'app') =>
    fetch(`http://127.0.0.1:${vhostPort}${path}`, { ...init, headers: { ...init.headers, Host: `${subdomain}.frps.test` } })

  beforeAll(async () => {
    openssl(dir, 'req', '-x509', '-newkey', 'ec', '-pkeyopt', 'ec_paramgen_curve:P-256', '-nodes', '-days', '1',
      '-subj', '/CN=e2e test CA', '-keyout', 'ca.key', '-out', 'ca.crt')
    openssl(dir, 'req', '-newkey', 'ec', '-pkeyopt', 'ec_paramgen_curve:P-256', '-nodes', '-subj', '/CN=frps.test',
      '-keyout', 'server.key', '-out', 'server.csr')
    writeFileSync(join(dir, 'ext.cnf'), 'subjectAltName=DNS:frps.test\n')
    openssl(dir, 'x509', '-req', '-in', 'server.csr', '-CA', 'ca.crt', '-CAkey', 'ca.key', '-CAcreateserial',
      '-days', '1', '-extfile', 'ext.cnf', '-out', 'server.crt')
    ca = readFileSync(join(dir, 'ca.crt'), 'utf8')

    ;[bindPort, vhostPort, backendPort] = await Promise.all([freePort(), freePort(), freePort()])
    backend = Bun.serve({
      hostname: '127.0.0.1',
      port: backendPort,
      fetch(req, server) {
        const url = new URL(req.url)
        if (url.pathname === '/ws') return server.upgrade(req) ? undefined : new Response('no upgrade', { status: 400 })
        if (url.pathname === '/sse') {
          let n = 0
          return new Response(new ReadableStream({
            async pull(c) { await Bun.sleep(200); c.enqueue(`data: ${n}\n\n`); if (++n === 3) c.close() },
          }), { headers: { 'content-type': 'text/event-stream' } })
        }
        if (url.pathname === '/big') return new Response(Buffer.alloc(5_000_000, 7))
        if (req.method === 'POST') return req.arrayBuffer().then(b => new Response(`posted ${b.byteLength}`))
        return new Response(`ok ${url.pathname}`)
      },
      websocket: { message(ws, m) { ws.send(typeof m === 'string' ? `echo ${m}` : m) } },
    })
    writeFileSync(join(dir, 'frps.toml'), [
      'bindAddr = "127.0.0.1"', `bindPort = ${bindPort}`, `vhostHTTPPort = ${vhostPort}`, 'subdomainHost = "frps.test"',
      'proxyBindAddr = "127.0.0.1"', `auth.token = "${token}"`, 'transport.tls.force = true',
      `transport.tls.certFile = "${join(dir, 'server.crt')}"`, `transport.tls.keyFile = "${join(dir, 'server.key')}"`,
    ].join('\n'))
    frps = spawn(FRPS, ['-c', join(dir, 'frps.toml')], { stdio: 'ignore' })
    for (let i = 0; i < 100; i++) {
      const up = await new Promise<boolean>(r => {
        const s = createServer().once('error', () => r(true)).listen(bindPort, '127.0.0.1', () => s.close(() => r(false)))
      })
      if (up) break
      await Bun.sleep(50)
    }
  })

  afterAll(() => {
    for (const c of clients) c.close()
    frps?.kill()
    backend?.stop(true)
  })

  test('logs in over verified TLS and serves HTTP through the vhost', async () => {
    const client = await started()
    expect(client.runId).toBeTruthy()
    const res = await via('/hello')
    expect(res.status).toBe(200)
    expect(await res.text()).toBe('ok /hello')
  })

  test('large request and response bodies arrive whole', async () => {
    const post = await via('/upload', { method: 'POST', body: Buffer.alloc(2_000_000, 1) })
    expect(await post.text()).toBe('posted 2000000')
    const big = Buffer.from(await (await via('/big')).arrayBuffer())
    expect(big.length).toBe(5_000_000)
    expect(big.every(b => b === 7)).toBe(true)
  })

  test('server-sent events stream as they are written, not at the end', async () => {
    const res = await via('/sse')
    const reader = res.body!.getReader()
    const times: number[] = []
    for (;;) {
      const { done } = await reader.read()
      if (done) break
      times.push(Date.now())
    }
    expect(times.length).toBeGreaterThanOrEqual(3)
    expect(times.at(-1)! - times[0]!).toBeGreaterThanOrEqual(300)
  })

  test('a WebSocket upgrades and carries text and large binary frames', async () => {
    const ws = new WebSocket(`ws://127.0.0.1:${vhostPort}/ws`, { headers: { Host: 'app.frps.test' } } as unknown as string[])
    const got: Array<string | number> = []
    await new Promise<void>((resolve, reject) => {
      ws.binaryType = 'arraybuffer'
      ws.onopen = () => { ws.send('ping'); ws.send(new Uint8Array(200_000)) }
      ws.onmessage = e => { got.push(typeof e.data === 'string' ? e.data : (e.data as ArrayBuffer).byteLength); if (got.length === 2) resolve() }
      ws.onerror = () => reject(new Error('websocket failed'))
    })
    ws.close()
    expect(got).toEqual(['echo ping', 200_000])
  })

  test('50 parallel requests all succeed', async () => {
    const results = await Promise.all(Array.from({ length: 50 }, (_, i) => via(`/p${i}`).then(r => r.text())))
    expect(results).toEqual(Array.from({ length: 50 }, (_, i) => `ok /p${i}`))
  })

  test('a wrong token is a login_rejected error', async () => {
    const error = await new FrpClient(options({ token: 'wrong' }, 'web-bad', 'bad')).start().catch(e => e)
    expect(error).toBeInstanceOf(FrpError)
    expect((error as FrpError).code).toBe('login_rejected')
  })

  test('an IP server address works without a server name (no SNI), verification off', async () => {
    await started({ tls: { verify: false } }, 'web-ip', 'ip')
    expect(await (await via('/ip', {}, 'ip')).text()).toBe('ok /ip')
  })

  test('an IP server address with verification on fails cleanly, not with a crash', async () => {
    const error = await new FrpClient(options({ tls: { ca } }, 'web-ipverify', 'ipverify')).start().catch(e => e)
    expect((error as FrpError).code).toBe('tls_failed')
  })

  test('a certificate the client does not trust is a tls_failed error', async () => {
    const error = await new FrpClient(options({ tls: { serverName: 'frps.test' } }, 'web-untrusted', 'untrusted')).start().catch(e => e)
    expect((error as FrpError).code).toBe('tls_failed')
  })

  test('a certificate for another name is a tls_failed error', async () => {
    const error = await new FrpClient(options({ tls: { serverName: 'other.test', ca } }, 'web-wrongname', 'wrongname')).start().catch(e => e)
    expect((error as FrpError).code).toBe('tls_failed')
  })

  test('a proxy name already in use is a proxy_rejected error', async () => {
    const error = await new FrpClient(options()).start().catch(e => e)
    expect((error as FrpError).code).toBe('proxy_rejected')
  })

  test('close() releases the proxy so a new client can take it at once', async () => {
    const first = await started({}, 'web-handoff', 'handoff')
    first.close()
    expect(await first.closed).toBeTruthy()
    await started({}, 'web-handoff', 'handoff')
    expect(await (await via('/again', {}, 'handoff')).text()).toBe('ok /again')
  })

  test('a new client with the old run id replaces a session that never closed', async () => {
    const old = await started({}, 'web-replace', 'replace')
    const replacement = await started({ runId: old.runId }, 'web-replace', 'replace')
    expect(replacement.runId).toBe(old.runId!)
    expect(await old.closed).toBeTruthy()
    expect(await (await via('/replaced', {}, 'replace')).text()).toBe('ok /replaced')
  })

  test('reaches frps through an http proxy with CONNECT', async () => {
    let tunnels = 0
    const proxy = createServer(client => {
      client.once('data', (head: Buffer) => {
        const [, target] = /^CONNECT (\S+) /.exec(head.toString()) ?? []
        const [host, port] = (target ?? '').split(':')
        const upstream = connect(Number(port), host!, () => {
          tunnels++
          client.write('HTTP/1.1 200 Connection established\r\n\r\n')
          client.pipe(upstream).pipe(client)
        })
        upstream.on('error', () => client.destroy())
      })
    })
    await new Promise<void>(r => proxy.listen(0, '127.0.0.1', r))
    try {
      await started({ httpProxy: `http://127.0.0.1:${(proxy.address() as { port: number }).port}` }, 'web-proxied', 'proxied')
      expect(tunnels).toBe(1)
      expect(await (await via('/through', {}, 'proxied')).text()).toBe('ok /through')
    } finally { proxy.close() }
  })

  test('keepalive pings are answered by frps and the session stays up', async () => {
    const events: string[] = []
    const client = await started({ timeouts: { keepAliveMs: 100, keepAliveTimeoutMs: 500 }, onEvent: e => events.push(e.type) }, 'web-ping', 'ping')
    await Bun.sleep(1_000)
    expect(events).not.toContain('closed')
    expect(await (await via('/still', {}, 'ping')).text()).toBe('ok /still')
    client.close()
  })

  test('a backend that is down fails the request and reports local_dial_failed', async () => {
    const events: string[] = []
    const deadPort = await freePort()
    const client = new FrpClient({
      ...options({}, 'web-dead', 'dead'),
      proxies: [{ name: 'web-dead', type: 'http', subdomain: 'dead', localIP: '127.0.0.1', localPort: deadPort }],
      onEvent: e => events.push(e.type),
    })
    clients.push(client)
    await client.start()
    const res = await via('/x', {}, 'dead')
    expect(res.status).toBeGreaterThanOrEqual(400)
    expect(events).toContain('local_dial_failed')
  })
})
