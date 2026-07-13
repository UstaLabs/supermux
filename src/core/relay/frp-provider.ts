import type { RelayProvider, RelayStatus } from "./provider"

export interface FrpChild { kill(): void; exited: Promise<unknown> }

const PARENT_BOUND_SH = [
  "parent=$PPID",
  '"$@" &',
  "child=$!",
  'stop() { trap - TERM INT EXIT; kill "$child" 2>/dev/null || true; wait "$child" 2>/dev/null || true; }',
  "trap stop TERM INT EXIT",
  'while kill -0 "$parent" 2>/dev/null && kill -0 "$child" 2>/dev/null; do sleep 1; done',
  "stop",
].join("\n")

/** Keep frpc tied to the broker even after SIGKILL; normal stop still uses FrpChild.kill(). */
export function parentBoundFrpcCommand(argv: string[], platform = process.platform): string[] {
  if (platform === "win32") return argv
  return ["/bin/sh", "-c", PARENT_BOUND_SH, "mux-frpc-parent", ...argv]
}

export interface FrpProviderOpts {
  identity: { hostId: string; publicKeyRaw: Buffer; sign(m: Buffer): Buffer }
  relayBase: string       // https://relay.supermux.dev (lease endpoint host)
  relayDomain: string     // relay.supermux.dev (subdomain suffix)
  localPort: number       // 9898
  fetchImpl?: (url: string, init?: { method?: string; headers?: Record<string, string>; body?: string }) => Promise<Response>
  getNonce: () => Promise<string>
  spawn: (argv: string[]) => FrpChild
  writeConfig: (toml: string) => string  // writes frpc.toml, returns path
  now?: () => number
  setTimer?: (fn: () => void, delayMs: number) => ReturnType<typeof setTimeout>
  clearTimer?: (timer: ReturnType<typeof setTimeout>) => void
}

export class FrpRelayProvider implements RelayProvider {
  private child: FrpChild | undefined
  private state: RelayStatus = { state: "disabled" }
  private desired = false
  private connecting = false
  private generation = 0
  private timer: ReturnType<typeof setTimeout> | undefined
  constructor(private readonly o: FrpProviderOpts) {}

  status(): RelayStatus { return this.state }

  async start(): Promise<void> {
    this.desired = true
    await this.connect()
  }

  private clearScheduled(): void {
    if (!this.timer) return
    ;(this.o.clearTimer ?? clearTimeout)(this.timer)
    this.timer = undefined
  }

  private schedule(fn: () => void, delayMs: number): void {
    this.clearScheduled()
    if (this.o.setTimer) this.timer = this.o.setTimer(fn, delayMs)
    else {
      this.timer = setTimeout(fn, delayMs)
      this.timer.unref()
    }
  }

  private async connect(): Promise<void> {
    if (!this.desired || this.connecting) return
    this.connecting = true
    const generation = ++this.generation
    this.clearScheduled()
    try { this.child?.kill() } catch { /* already gone */ }
    this.child = undefined
    this.state = { state: "connecting" }
    const f = this.o.fetchImpl ?? fetch
    try {
      const nonce = await this.o.getNonce()
      const signature = this.o.identity.sign(Buffer.from(nonce)).toString("base64url")
      const res = await f(`${this.o.relayBase}/relay/lease`, {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({
          hostId: this.o.identity.hostId,
          publicKey: this.o.identity.publicKeyRaw.toString("base64url"),
          nonce,
          signature,
        }),
      })
      if (!res.ok) { this.state = { state: "error", detail: `lease ${res.status}` }; return }
      const { lease, expiresAt } = (await res.json()) as { lease: string; expiresAt?: number }
      if (!this.desired || generation !== this.generation) return
      const subdomain = `h-${this.o.identity.hostId}`
      const serverHost = new URL(this.o.relayBase).hostname
      const toml = [
        `serverAddr = ${JSON.stringify(serverHost)}`,
        "serverPort = 7000",
        "transport.tls.enable = true",
        "[metadatas]",
        `lease = ${JSON.stringify(lease)}`,
        "[[proxies]]",
        'name = "web"',
        'type = "http"',
        'localIP = "127.0.0.1"',
        `localPort = ${this.o.localPort}`,
        `subdomain = ${JSON.stringify(subdomain)}`,
        `metadatas.lease = ${JSON.stringify(lease)}`,
      ].join("\n")
      const cfgPath = this.o.writeConfig(toml)
      this.child = this.o.spawn(["frpc", "-c", cfgPath])
      this.state = { state: "online", relayUrl: `https://${subdomain}.${this.o.relayDomain}` }
      const child = this.child
      void child.exited.then(() => {
        if (!this.desired || generation !== this.generation || this.child !== child) return
        this.child = undefined
        this.state = { state: "connecting" }
        this.schedule(() => { void this.connect() }, 1_000)
      })
      const leaseExpiry = expiresAt ?? Number(lease.split(".")[1])
      if (Number.isFinite(leaseExpiry)) {
        const renewIn = Math.min(2_147_000_000, Math.max(30_000, leaseExpiry - (this.o.now?.() ?? Date.now()) - 5 * 60_000))
        this.schedule(() => { void this.connect() }, renewIn)
      }
    } catch (e) {
      this.state = { state: "error", detail: String(e) }
      if (this.desired && generation === this.generation) this.schedule(() => { void this.connect() }, 5_000)
    } finally {
      this.connecting = false
    }
  }

  async stop(): Promise<void> {
    this.desired = false
    this.generation++
    this.clearScheduled()
    try { this.child?.kill() } catch { /* already gone */ }
    this.child = undefined
    this.state = { state: "disabled" }
  }
}
