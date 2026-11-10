// A minimal Chrome DevTools Protocol client for the render check (check.ts): launch the system Chrome, connect, send
// commands, hear events, and talk to targets through flattened sessions.
//
// Transport: WebSocket on 127.0.0.1, not --remote-debugging-pipe. The pipe wants the child to hold fds 3 and 4, and Bun.spawn
// can't give it that: `stdio: [.., "pipe", "pipe"]` returns parent-side numbers that don't work as readable or
// writable streams (net.Socket on them is "closed", a read polls forever; probed on Bun 1.4.4), so the transport is
// --remote-debugging-port=0 on 127.0.0.1, whose chosen port and browser-endpoint path Chrome writes to
// <profile>/DevToolsActivePort. The endpoint path holds a random id, so another local process can't guess it.
import { mkdtemp, readFile, rm } from "node:fs/promises"
import { tmpdir } from "node:os"
import { join } from "node:path"

/* eslint-disable @typescript-eslint/no-explicit-any */
type Handler = (params: any) => void

interface Pending { resolve: (v: any) => void; reject: (e: Error) => void; method: string; sessionId?: string }

export class CdpError extends Error {}

/** A connection to the browser's DevTools endpoint. Every call is answered, or rejected when the connection or session goes. */
export class CdpClient {
  private nextId = 1
  private pending = new Map<number, Pending>()
  private handlers = new Map<string, Set<Handler>>()
  private closedFlag = false
  private resolveClosed!: () => void
  /** Resolves when the connection is gone, however it went. */
  readonly closed = new Promise<void>((r) => { this.resolveClosed = r })

  private constructor(private ws: WebSocket) {
    ws.onmessage = (e) => this.onMessage(typeof e.data === "string" ? e.data : Buffer.from(e.data as ArrayBuffer).toString())
    ws.onclose = () => this.shutdown("the browser connection closed")
    ws.onerror = () => this.shutdown("the browser connection failed")
  }

  static connect(url: string, timeoutMs = 10_000): Promise<CdpClient> {
    return new Promise((resolve, reject) => {
      const ws = new WebSocket(url)
      const timer = setTimeout(() => { ws.close(); reject(new CdpError("timed out connecting to the browser")) }, timeoutMs)
      ws.onopen = () => { clearTimeout(timer); resolve(new CdpClient(ws)) }
      ws.onerror = () => { clearTimeout(timer); reject(new CdpError("could not connect to the browser")) }
    })
  }

  get isClosed(): boolean { return this.closedFlag }

  send<T = any>(method: string, params: object = {}, sessionId?: string): Promise<T> {
    if (this.closedFlag) return Promise.reject(new CdpError(`${method}: the browser connection is closed`))
    const id = this.nextId++
    return new Promise<T>((resolve, reject) => {
      this.pending.set(id, { resolve, reject, method, ...(sessionId ? { sessionId } : {}) })
      try { this.ws.send(JSON.stringify({ id, method, params, ...(sessionId ? { sessionId } : {}) })) } catch (e) {
        this.pending.delete(id)
        reject(new CdpError(`${method}: ${(e as Error).message}`))
      }
    })
  }

  /** Listen for an event (of one session, or of the browser itself when no session is given). Returns the unsubscribe. */
  on(event: string, handler: Handler, sessionId?: string): () => void {
    const key = `${sessionId ?? ""}|${event}`
    let set = this.handlers.get(key)
    if (!set) this.handlers.set(key, (set = new Set()))
    set.add(handler)
    return () => { set!.delete(handler) }
  }

  /** Attach to a target with a flattened session: its commands and events travel on this connection, tagged by session. */
  async attach(targetId: string): Promise<CdpSession> {
    const { sessionId } = await this.send<{ sessionId: string }>("Target.attachToTarget", { targetId, flatten: true })
    return new CdpSession(this, sessionId)
  }

  async close(): Promise<void> {
    if (!this.closedFlag) { try { this.ws.close() } catch { /* already gone */ } this.shutdown("the browser connection was closed") }
    await this.closed
  }

  private onMessage(text: string): void {
    let m: any
    try { m = JSON.parse(text) } catch { return }
    if (typeof m.id === "number") {
      const p = this.pending.get(m.id)
      if (!p) return
      this.pending.delete(m.id)
      if (m.error) p.reject(new CdpError(`${p.method}: ${m.error.message}${m.error.data ? ` (${m.error.data})` : ""}`))
      else p.resolve(m.result ?? {})
      return
    }
    if (typeof m.method !== "string") return
    if (m.method === "Target.detachedFromTarget" && m.params?.sessionId) this.rejectSession(m.params.sessionId, "the page went away")
    if (m.method === "Inspector.targetCrashed" && m.sessionId) this.rejectSession(m.sessionId, "the page crashed")
    for (const h of [...(this.handlers.get(`${m.sessionId ?? ""}|${m.method}`) ?? [])]) {
      try { h(m.params ?? {}) } catch { /* a listener's bug is not the connection's */ }
    }
  }

  private rejectSession(sessionId: string, why: string): void {
    for (const [id, p] of this.pending) {
      if (p.sessionId !== sessionId) continue
      this.pending.delete(id)
      p.reject(new CdpError(`${p.method}: ${why}`))
    }
  }

  private shutdown(why: string): void {
    if (this.closedFlag) return
    this.closedFlag = true
    for (const [id, p] of this.pending) { this.pending.delete(id); p.reject(new CdpError(`${p.method}: ${why}`)) }
    this.handlers.clear()
    this.resolveClosed()
  }
}

/** One target's commands and events. */
export class CdpSession {
  constructor(readonly client: CdpClient, readonly id: string) {}
  send<T = any>(method: string, params: object = {}): Promise<T> { return this.client.send<T>(method, params, this.id) }
  on(event: string, handler: Handler): () => void { return this.client.on(event, handler, this.id) }
}

export interface ChromeProcess {
  client: CdpClient
  /** Close the browser politely (Browser.close), kill it if it lingers, and remove its profile. */
  close(): Promise<void>
}

const sleep = (ms: number) => new Promise<void>((r) => setTimeout(r, ms))

/** Launch `executable` headless with a throwaway profile and connect to it. */
export async function launchChrome(executable: string, o: { args?: string[]; env?: Record<string, string | undefined>; timeoutMs?: number } = {}): Promise<ChromeProcess> {
  const dir = await mkdtemp(join(tmpdir(), "supermux-check-"))
  const proc = Bun.spawn([executable, "--headless=new", "--remote-debugging-port=0", "--remote-debugging-address=127.0.0.1",
    `--user-data-dir=${dir}`, "--no-first-run", "--no-default-browser-check",
    // As Playwright launched it before: Chrome's own sandbox needs user namespaces, which the hosts we run on may not allow.
    "--no-sandbox", ...(o.args ?? []), "about:blank"],
  { stdin: "ignore", stdout: "ignore", stderr: "ignore", env: o.env as Record<string, string> | undefined })
  let exited = false
  void proc.exited.then(() => { exited = true })
  const cleanup = async () => { await rm(dir, { recursive: true, force: true, maxRetries: 5, retryDelay: 100 }).catch(() => {}) }
  try {
    const deadline = Date.now() + (o.timeoutMs ?? 15_000)
    let url: string | undefined
    while (!url) {
      if (exited) throw new CdpError("the browser exited before it was ready")
      if (Date.now() > deadline) throw new CdpError("the browser did not start in time")
      const [port, path] = (await readFile(join(dir, "DevToolsActivePort"), "utf8").catch(() => "")).split("\n")
      if (port && path) url = `ws://127.0.0.1:${port}${path}`
      else await sleep(25)
    }
    const client = await CdpClient.connect(url)
    // Gone however it went (Browser.close, a crash, a kill): the connection closing is the signal; reap and clean up.
    void client.closed.then(async () => {
      if (!exited) await Promise.race([proc.exited, sleep(3000)])
      if (!exited) proc.kill("SIGKILL")
      await proc.exited
      await cleanup()
    })
    return {
      client,
      async close() {
        if (!client.isClosed) await client.send("Browser.close").catch(() => {})
        await Promise.race([client.closed, sleep(2000)])
        await client.close()
        await proc.exited
        await cleanup()
      },
    }
  } catch (e) {
    proc.kill("SIGKILL")
    await proc.exited
    await cleanup()
    throw e
  }
}
