import type { RelayProvider, RelayStatus } from "./provider"

export interface FrpChild { kill(): void; exited: Promise<unknown> }
export interface FrpProviderOpts {
  identity: { hostId: string; sign(m: Buffer): Buffer }
  relayBase: string       // https://relay.supermux.dev (lease endpoint host)
  relayDomain: string     // relay.supermux.dev (subdomain suffix)
  localPort: number       // 9898
  fetchImpl?: (url: string, init?: { method?: string; headers?: Record<string, string>; body?: string }) => Promise<Response>
  getNonce: () => Promise<string>
  spawn: (argv: string[]) => FrpChild
  writeConfig: (ini: string) => string  // writes frpc.ini, returns path
}

export class FrpRelayProvider implements RelayProvider {
  private child: FrpChild | undefined
  private state: RelayStatus = { state: "disabled" }
  constructor(private readonly o: FrpProviderOpts) {}

  status(): RelayStatus { return this.state }

  async start(): Promise<void> {
    this.state = { state: "connecting" }
    const f = this.o.fetchImpl ?? fetch
    try {
      const nonce = await this.o.getNonce()
      const signature = this.o.identity.sign(Buffer.from(nonce)).toString("base64url")
      const res = await f(`${this.o.relayBase}/relay/lease`, {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({ hostId: this.o.identity.hostId, nonce, signature }),
      })
      if (!res.ok) { this.state = { state: "error", detail: `lease ${res.status}` }; return }
      const { lease } = (await res.json()) as { lease: string }
      const subdomain = `h-${this.o.identity.hostId}`
      const ini = [
        "[common]",
        `server_addr = ${this.o.relayDomain}`,
        "server_port = 7000",
        `metadatas.lease = ${lease}`,
        "[web]",
        "type = http",
        "local_ip = 127.0.0.1",
        `local_port = ${this.o.localPort}`,
        `subdomain = ${subdomain}`,
        `metadatas.lease = ${lease}`,
      ].join("\n")
      const cfgPath = this.o.writeConfig(ini)
      this.child = this.o.spawn(["frpc", "-c", cfgPath])
      this.state = { state: "online", relayUrl: `https://${subdomain}.${this.o.relayDomain}` }
    } catch (e) {
      this.state = { state: "error", detail: String(e) }
    }
  }

  async stop(): Promise<void> {
    try { this.child?.kill() } catch { /* already gone */ }
    this.child = undefined
    this.state = { state: "disabled" }
  }
}
