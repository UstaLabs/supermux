import { spawn, type ChildProcess } from "node:child_process"
import { createInterface } from "node:readline"
import { bridgeEntry } from "../src/mcp/host.js"

type Message = { jsonrpc: "2.0"; id?: string | number; method?: string; params?: any; result?: any; error?: any }

/** Drives a real bridge process the way an agent's MCP client would (stdio, newline JSON-RPC). */
export class BridgeClient {
  readonly child: ChildProcess
  readonly received: Message[] = []
  readonly stderr: string[] = []
  private next = 1
  private readonly waiting = new Map<string, (message: Message) => void>()
  readonly exited: Promise<number | null>

  constructor(env: { socket: string; session: string; token: string; server: string; retryMaxMs?: number }) {
    this.child = spawn(process.execPath, [bridgeEntry(), "--server", env.server], {
      stdio: ["pipe", "pipe", "pipe"],
      env: {
        PATH: process.env.PATH ?? "", SUPERMUX_MCP_SOCKET: env.socket, SUPERMUX_MCP_SESSION: env.session, SUPERMUX_MCP_TOKEN: env.token,
        SUPERMUX_MCP_RETRY_MAX_MS: String(env.retryMaxMs ?? 200),
      },
    })
    this.exited = new Promise(resolve => this.child.once("exit", code => resolve(code)))
    createInterface({ input: this.child.stdout! }).on("line", line => {
      const message = JSON.parse(line) as Message
      this.received.push(message)
      if (message.id !== undefined && message.method === undefined) {
        const resolve = this.waiting.get(JSON.stringify(message.id))
        this.waiting.delete(JSON.stringify(message.id))
        resolve?.(message)
      }
    })
    createInterface({ input: this.child.stderr! }).on("line", line => this.stderr.push(line))
  }

  send(message: Message): void { this.child.stdin!.write(JSON.stringify(message) + "\n") }

  request(method: string, params: unknown = {}, timeoutMs = 10_000): Promise<Message> {
    const id = this.next++
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error(`${method} timed out; stderr: ${this.stderr.join(" | ")}`)), timeoutMs)
      this.waiting.set(JSON.stringify(id), message => { clearTimeout(timer); resolve(message) })
      this.send({ jsonrpc: "2.0", id, method, params: params as any })
    })
  }

  notify(method: string, params: unknown = {}): void { this.send({ jsonrpc: "2.0", method, params: params as any }) }

  async initialize(): Promise<Message> {
    const response = await this.request("initialize", { protocolVersion: "2025-06-18", capabilities: {}, clientInfo: { name: "test-client", version: "1" } })
    this.notify("notifications/initialized")
    return response
  }

  notifications(method: string): Message[] { return this.received.filter(message => message.method === method) }

  async close(): Promise<void> {
    if (this.child.exitCode !== null || this.child.signalCode !== null) return
    this.child.stdin!.end()
    const timer = setTimeout(() => this.child.kill("SIGKILL"), 3000)
    await this.exited
    clearTimeout(timer)
  }
}

export async function until(check: () => boolean | Promise<boolean>, timeoutMs = 10_000, what = "condition"): Promise<void> {
  const start = Date.now()
  while (!(await check())) {
    if (Date.now() - start > timeoutMs) throw new Error(`timed out waiting for ${what}`)
    await new Promise(resolve => setTimeout(resolve, 20))
  }
}
