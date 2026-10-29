/**
 * An frp client session, after frpc 0.61's client/service.go, connector.go and control.go: one TLS connection
 * to frps carrying a yamux session; the control stream logs in, then (encrypted) registers the proxies and
 * receives ReqWorkConn; each work connection is a new stream joined to the proxy's local address.
 *
 * Unlike frpc it does not reconnect itself: `closed` settles when the session ends, and the owner starts a
 * new client (passing `runId` to replace the old session at once, as frpc's reconnect does). Unlike frpc it
 * verifies the server's certificate unless told not to.
 */
import { connect as netConnect, isIP, type Socket } from 'node:net'
import { arch, platform } from 'node:os'
import { connect as tlsConnect, type TLSSocket } from 'node:tls'
import type { Duplex } from 'node:stream'
import { cryptoChannel, cryptoKey, encodeMessage, MsgType, privilegeKey, readMessage, type CryptoChannel } from './protocol.js'
import { YamuxSession, type YamuxStream } from './yamux.js'

export interface HttpProxyConfig {
  /** Proxy names are global on the server: make them unique (frps refuses a second "web"). */
  name: string
  type: 'http'
  subdomain?: string
  customDomains?: string[]
  metadatas?: Record<string, string>
  localIP: string
  localPort: number
}

export interface FrpClientOptions {
  serverAddr: string
  serverPort: number
  /** TLS to frps, on by default (as in frpc). */
  tls?: {
    enable?: boolean
    /** Defaults to serverAddr. */
    serverName?: string
    /** Check the server's certificate against `ca` or the system roots (default true; frpc does not). */
    verify?: boolean
    ca?: string | Buffer
  }
  token?: string
  user?: string
  metadatas?: Record<string, string>
  proxies: HttpProxyConfig[]
  /** Work connections frps keeps ready (frpc's transport.poolCount, default 1). */
  poolCount?: number
  /** A previous session's run id: frps then closes that session at once instead of waiting for it to time out. */
  runId?: string
  /** An http:// proxy to reach frps through with CONNECT (frpc reads http_proxy). */
  httpProxy?: string
  /** The version reported at login (frps only logs it). */
  version?: string
  timeouts?: Partial<FrpTimeouts>
  /** Events for logging. Never carries the token or metadatas. */
  onEvent?: (event: FrpEvent) => void
}

export interface FrpTimeouts {
  dialMs: number
  loginMs: number
  /** How long frps may take to answer a NewProxy (frpc's waitResponseTimeout). */
  proxyMs: number
  localDialMs: number
  keepAliveMs: number
  keepAliveTimeoutMs: number
  maxStreamWindow: number
}

const DEFAULTS: FrpTimeouts = {
  dialMs: 10_000,
  loginMs: 10_000,
  proxyMs: 20_000,
  localDialMs: 10_000,
  keepAliveMs: 30_000,
  keepAliveTimeoutMs: 10_000,
  maxStreamWindow: 6 * 1024 * 1024,
}

export type FrpEvent =
  | { type: 'connected'; runId: string }
  | { type: 'proxy_started'; name: string; remoteAddr: string }
  | { type: 'work_conn_failed'; reason: string }
  | { type: 'local_dial_failed'; name: string; code?: string }
  | { type: 'closed'; reason: string }

export type FrpErrorCode =
  | 'dial_failed' | 'proxy_dial_failed' | 'tls_failed' | 'login_timeout' | 'login_rejected'
  | 'proxy_timeout' | 'proxy_rejected' | 'protocol_error' | 'closed'

export class FrpError extends Error {
  constructor(readonly code: FrpErrorCode, message: string, options?: { cause?: unknown }) {
    super(message, options)
    this.name = 'FrpError'
  }
}

function timeout<T>(promise: Promise<T>, ms: number, error: () => Error): Promise<T> {
  let timer: ReturnType<typeof setTimeout> | undefined
  return Promise.race([
    promise,
    new Promise<never>((_, reject) => { timer = setTimeout(() => reject(error()), ms) }),
  ]).finally(() => clearTimeout(timer))
}

function errnoCode(error: unknown): string | undefined {
  const code = (error as NodeJS.ErrnoException | undefined)?.code
  return typeof code === 'string' ? code : undefined
}

function connectTcp(host: string, port: number, ms: number): Promise<Socket> {
  return new Promise((resolve, reject) => {
    const socket = netConnect({ host, port })
    const timer = setTimeout(() => { socket.destroy(); reject(Object.assign(new Error(`connect ${host}:${port} timed out`), { code: 'ETIMEDOUT' })) }, ms)
    socket.once('connect', () => { clearTimeout(timer); socket.off('error', onError); resolve(socket) })
    const onError = (error: Error) => { clearTimeout(timer); reject(error) }
    socket.once('error', onError)
  })
}

/** A tunnel to host:port through an http:// proxy (CONNECT), as frpc's ProxyURL. */
async function connectThroughProxy(proxyUrl: string, host: string, port: number, ms: number): Promise<Socket> {
  const proxy = new URL(proxyUrl)
  if (proxy.protocol !== 'http:') throw new FrpError('proxy_dial_failed', `unsupported proxy scheme ${proxy.protocol}`)
  const socket = await connectTcp(proxy.hostname, Number(proxy.port || 80), ms)
  const auth = proxy.username
    ? `Proxy-Authorization: Basic ${Buffer.from(`${decodeURIComponent(proxy.username)}:${decodeURIComponent(proxy.password)}`).toString('base64')}\r\n`
    : ''
  const target = `${host.includes(':') ? `[${host}]` : host}:${port}`
  socket.write(`CONNECT ${target} HTTP/1.1\r\nHost: ${target}\r\n${auth}\r\n`)
  const head = await timeout(new Promise<Buffer>((resolve, reject) => {
    let buffer = Buffer.alloc(0)
    const onData = (chunk: Buffer) => {
      buffer = Buffer.concat([buffer, chunk])
      const end = buffer.indexOf('\r\n\r\n')
      if (end < 0) {
        if (buffer.length > 16_384) reject(new FrpError('proxy_dial_failed', 'proxy response too long'))
        return
      }
      socket.off('data', onData)
      socket.pause()
      if (end + 4 < buffer.length) socket.unshift(buffer.subarray(end + 4))
      resolve(buffer.subarray(0, end))
    }
    socket.on('data', onData)
    socket.once('error', reject)
    socket.once('close', () => reject(new FrpError('proxy_dial_failed', 'proxy closed the connection')))
  }), ms, () => new FrpError('proxy_dial_failed', 'proxy did not answer CONNECT'))
  const status = /^HTTP\/1\.[01] (\d{3})/.exec(head.toString('latin1'))?.[1]
  if (status !== '200') {
    socket.destroy()
    throw new FrpError('proxy_dial_failed', `proxy refused CONNECT (${status ?? 'no status'})`)
  }
  return socket
}

/** Joins a work stream and a local connection; when either side ends, both close (golib's io.Join). */
function join(remote: Duplex, local: Socket): void {
  let done = false
  const finish = () => {
    if (done) return
    done = true
    // Let what each side already queued flush, then close both.
    local.end()
    remote.end()
    setImmediate(() => { local.destroy(); remote.destroy() })
  }
  remote.pipe(local, { end: false })
  local.pipe(remote, { end: false })
  for (const side of [remote, local]) {
    side.once('end', finish)
    side.once('close', finish)
    side.once('error', finish)
  }
}

export class FrpClient {
  private readonly options: FrpClientOptions
  private readonly timeouts: FrpTimeouts
  private session?: YamuxSession
  private control?: CryptoChannel
  private readonly proxies = new Map<string, HttpProxyConfig>()
  private readonly pendingProxies = new Map<string, { resolve: (remoteAddr: string) => void; reject: (error: Error) => void }>()
  private closing = false
  private resolveClosed!: (reason: string) => void
  /** Settles with why the session ended, once it has (also after close()). */
  readonly closed: Promise<string>
  runId?: string

  constructor(options: FrpClientOptions) {
    this.options = options
    this.timeouts = { ...DEFAULTS, ...options.timeouts }
    this.runId = options.runId
    for (const proxy of options.proxies) {
      if (proxy.type !== 'http') throw new TypeError(`unsupported proxy type ${String(proxy.type)}`)
      if (this.proxies.has(proxy.name)) throw new TypeError(`duplicate proxy name ${proxy.name}`)
      this.proxies.set(proxy.name, proxy)
    }
    this.closed = new Promise(resolve => { this.resolveClosed = resolve })
  }

  /** Connects, logs in and registers every proxy; resolves with the run id once all are started. */
  async start(): Promise<string> {
    if (this.session) throw new Error('FrpClient.start called twice')
    try {
      const conn = await this.dial()
      const session = new YamuxSession(conn, {
        client: true,
        keepAliveMs: this.timeouts.keepAliveMs,
        timeoutMs: this.timeouts.keepAliveTimeoutMs,
        maxStreamWindow: this.timeouts.maxStreamWindow,
      })
      this.session = session
      session.onClose(error => this.ended(error.message))
      const runId = await this.login(session)
      await this.registerProxies()
      return runId
    } catch (error) {
      this.close()
      throw error
    }
  }

  /** Ends the session: tells frps the proxies are gone, then closes the connection. */
  close(): void {
    if (this.closing) return
    this.closing = true
    try {
      for (const name of this.proxies.keys()) this.control?.write(MsgType.CloseProxy, { proxy_name: name })
    } catch { /* already gone */ }
    if (this.session) this.session.close()
    else this.ended('closed')
  }

  private ended(reason: string): void {
    for (const pending of this.pendingProxies.values()) pending.reject(new FrpError('closed', `session ended: ${reason}`))
    this.pendingProxies.clear()
    this.options.onEvent?.({ type: 'closed', reason })
    this.resolveClosed(reason)
  }

  private async dial(): Promise<Duplex> {
    const { serverAddr, serverPort, httpProxy } = this.options
    let socket: Socket
    try {
      socket = httpProxy
        ? await connectThroughProxy(httpProxy, serverAddr, serverPort, this.timeouts.dialMs)
        : await connectTcp(serverAddr, serverPort, this.timeouts.dialMs)
    } catch (error) {
      if (error instanceof FrpError) throw error
      throw new FrpError('dial_failed', `could not reach ${serverAddr}:${serverPort}${errnoCode(error) ? ` (${errnoCode(error)})` : ''}`, { cause: error })
    }
    socket.setKeepAlive(true, 7_200_000)
    socket.setNoDelay(true)
    const tls = this.options.tls ?? {}
    if (tls.enable === false) return socket
    const serverName = tls.serverName ?? serverAddr
    return new Promise<TLSSocket>((resolve, reject) => {
      let secure: TLSSocket
      try {
        secure = tlsConnect({
          socket,
          // SNI takes host names only; an IP address is still checked against the certificate (via host).
          ...(isIP(serverName) ? {} : { servername: serverName }),
          host: serverName,
          rejectUnauthorized: tls.verify !== false,
          ...(tls.ca ? { ca: tls.ca } : {}),
        })
      } catch (error) {
        socket.destroy()
        reject(new FrpError('tls_failed', `TLS to ${serverAddr} failed: ${(error as Error).message}`, { cause: error }))
        return
      }
      const timer = setTimeout(() => { secure.destroy(); reject(new FrpError('tls_failed', 'TLS handshake timed out')) }, this.timeouts.dialMs)
      secure.once('secureConnect', () => { clearTimeout(timer); secure.off('error', onError); resolve(secure) })
      const onError = (error: Error) => {
        clearTimeout(timer)
        socket.destroy()
        reject(new FrpError('tls_failed', `TLS to ${serverAddr} failed: ${errnoCode(error) ?? error.message}`, { cause: error }))
      }
      secure.once('error', onError)
    })
  }

  private async login(session: YamuxSession): Promise<string> {
    const stream = session.open()
    const token = this.options.token ?? ''
    const timestamp = Math.floor(Date.now() / 1000)
    stream.write(encodeMessage(MsgType.Login, {
      version: this.options.version ?? '0.61.1',
      os: platform(),
      arch: arch(),
      ...(this.options.user ? { user: this.options.user } : {}),
      privilege_key: privilegeKey(token, timestamp),
      timestamp,
      ...(this.runId ? { run_id: this.runId } : {}),
      ...(this.options.metadatas ? { metas: this.options.metadatas } : {}),
      pool_count: this.options.poolCount ?? 1,
    }))
    let response
    try {
      response = await timeout(readMessage(stream), this.timeouts.loginMs, () => new FrpError('login_timeout', 'frps did not answer the login'))
    } catch (error) {
      if (error instanceof FrpError) throw error
      throw new FrpError('protocol_error', `login failed: ${(error as Error).message}`, { cause: error })
    }
    if (response.type !== MsgType.LoginResp) throw new FrpError('protocol_error', `expected LoginResp, got message type ${response.type}`)
    if (typeof response.body.error === 'string' && response.body.error) throw new FrpError('login_rejected', response.body.error)
    const runId = response.body.run_id
    if (typeof runId !== 'string' || !runId) throw new FrpError('protocol_error', 'LoginResp carried no run id')
    this.runId = runId
    this.control = cryptoChannel(stream, cryptoKey(token))
    void this.controlLoop(this.control)
    this.options.onEvent?.({ type: 'connected', runId })
    return runId
  }

  private async registerProxies(): Promise<void> {
    const control = this.control!
    const started = [...this.proxies.values()].map(proxy => {
      const answer = new Promise<string>((resolve, reject) => this.pendingProxies.set(proxy.name, { resolve, reject }))
      control.write(MsgType.NewProxy, {
        proxy_name: proxy.name,
        proxy_type: proxy.type,
        ...(proxy.subdomain ? { subdomain: proxy.subdomain } : {}),
        ...(proxy.customDomains?.length ? { custom_domains: proxy.customDomains } : {}),
        ...(proxy.metadatas ? { metas: proxy.metadatas } : {}),
      })
      return timeout(answer, this.timeouts.proxyMs, () => new FrpError('proxy_timeout', `frps did not answer proxy ${proxy.name}`))
        .then(remoteAddr => this.options.onEvent?.({ type: 'proxy_started', name: proxy.name, remoteAddr }))
    })
    await Promise.all(started)
  }

  private async controlLoop(control: CryptoChannel): Promise<void> {
    try {
      for (;;) {
        const message = await readMessage(control.reader)
        if (message.type === MsgType.ReqWorkConn) void this.workConn()
        else if (message.type === MsgType.NewProxyResp) this.proxyAnswered(message.body)
        // Pong and anything newer: nothing to do.
      }
    } catch (error) {
      if (!this.closing) this.session?.fail(new Error(`control stream ended: ${(error as Error).message}`))
    }
  }

  private proxyAnswered(body: Record<string, unknown>): void {
    const name = typeof body.proxy_name === 'string' ? body.proxy_name : ''
    const pending = this.pendingProxies.get(name)
    if (!pending) return
    this.pendingProxies.delete(name)
    if (typeof body.error === 'string' && body.error) pending.reject(new FrpError('proxy_rejected', `proxy ${name}: ${body.error}`))
    else pending.resolve(typeof body.remote_addr === 'string' ? body.remote_addr : '')
  }

  private async workConn(): Promise<void> {
    const session = this.session
    if (!session || session.closed || !this.runId) return
    let stream: YamuxStream | undefined
    try {
      stream = session.open()
      stream.write(encodeMessage(MsgType.NewWorkConn, { run_id: this.runId }))
      const start = await readMessage(stream)
      if (start.type !== MsgType.StartWorkConn) throw new Error(`expected StartWorkConn, got message type ${start.type}`)
      if (typeof start.body.error === 'string' && start.body.error) throw new Error(start.body.error)
      const proxy = this.proxies.get(String(start.body.proxy_name))
      if (!proxy) throw new Error('StartWorkConn for an unknown proxy')
      let local: Socket
      try {
        local = await connectTcp(proxy.localIP, proxy.localPort, this.timeouts.localDialMs)
      } catch (error) {
        this.options.onEvent?.({ type: 'local_dial_failed', name: proxy.name, code: errnoCode(error) })
        stream.destroy()
        return
      }
      local.setNoDelay(true)
      join(stream, local)
    } catch (error) {
      stream?.destroy()
      if (!session.closed) this.options.onEvent?.({ type: 'work_conn_failed', reason: (error as Error).message })
    }
  }
}
