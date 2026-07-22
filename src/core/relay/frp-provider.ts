import type { RelayProvider, RelayStatus } from "./provider"
import { hostRelayUrl } from "./public-url"
import type { Logger } from "../../shared/log"

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
  log?: Pick<Logger, "info" | "warn">
}

type AcquireTrigger = "startup" | "renewal" | "audit" | "child_exit" | "retry"
type AcquireFailureCode =
  | "nonce_failed"
  | "lease_request_failed"
  | "lease_http_error"
  | "lease_response_invalid"
  | "config_write_failed"
  | "frpc_spawn_failed"
type AcquireAttempt = { generation: number; token: symbol }

const AUDIT_INTERVAL_MS = 300_000
const RETRY_DELAYS_MS = [5_000, 10_000, 20_000, 30_000, 60_000, 120_000, 300_000] as const
const MAX_TIMER_DELAY_MS = 2_147_483_647

class LeaseHttpError extends Error {
  constructor(readonly status: number) {
    super(`lease ${status}`)
  }
}

export class FrpRelayProvider implements RelayProvider {
  private child: FrpChild | undefined
  private state: RelayStatus = { state: "disabled" }
  private desired = false
  private activeAttempt: AcquireAttempt | undefined
  private generation = 0
  private renewTimer: ReturnType<typeof setTimeout> | undefined
  private auditTimer: ReturnType<typeof setTimeout> | undefined
  private retryTimer: ReturnType<typeof setTimeout> | undefined
  private leaseExpiry: number | undefined
  private renewalDueAt: number | undefined
  private retryIndex = 0
  constructor(private readonly o: FrpProviderOpts) {}

  status(): RelayStatus { return this.state }

  async start(): Promise<void> {
    if (this.desired) return
    this.desired = true
    this.generation++
    this.state = { state: "connecting" }
    await this.acquire("startup")
  }

  private emit(level: "info" | "warn", event: string, fields?: Record<string, unknown>): void {
    try { this.o.log?.[level](event, fields) } catch { /* observers cannot disrupt relay recovery */ }
  }

  private makeTimer(fn: () => void, delayMs: number): ReturnType<typeof setTimeout> {
    if (this.o.setTimer) return this.o.setTimer(fn, delayMs)
    const timer = setTimeout(fn, delayMs)
    timer.unref()
    return timer
  }

  private clearRenewTimer(): void {
    if (this.renewTimer === undefined) return
    ;(this.o.clearTimer ?? clearTimeout)(this.renewTimer)
    this.renewTimer = undefined
  }

  private clearAuditTimer(): void {
    if (this.auditTimer === undefined) return
    ;(this.o.clearTimer ?? clearTimeout)(this.auditTimer)
    this.auditTimer = undefined
  }

  private clearRetryTimer(): void {
    if (this.retryTimer === undefined) return
    ;(this.o.clearTimer ?? clearTimeout)(this.retryTimer)
    this.retryTimer = undefined
  }

  private scheduleRenewal(delayMs: number): void {
    this.clearRenewTimer()
    let timer: ReturnType<typeof setTimeout>
    timer = this.makeTimer(() => {
      if (this.renewTimer !== timer) return
      this.renewTimer = undefined
      void this.acquire("renewal")
    }, delayMs)
    this.renewTimer = timer
  }

  private scheduleAudit(): void {
    this.clearAuditTimer()
    let timer: ReturnType<typeof setTimeout>
    timer = this.makeTimer(() => {
      if (this.auditTimer !== timer) return
      this.auditTimer = undefined
      if (!this.desired || this.renewalDueAt === undefined) return
      if ((this.o.now?.() ?? Date.now()) < this.renewalDueAt || this.hasCurrentAttempt() || this.retryTimer !== undefined) {
        this.scheduleAudit()
        return
      }
      this.scheduleAudit()
      this.emit("warn", "relay_lease_audit_recovery", { hostId: this.o.identity.hostId })
      void this.acquire("audit")
    }, AUDIT_INTERVAL_MS)
    this.auditTimer = timer
  }

  private scheduleRetry(delayMs: number, trigger: AcquireTrigger): void {
    this.clearRetryTimer()
    let timer: ReturnType<typeof setTimeout>
    timer = this.makeTimer(() => {
      if (this.retryTimer !== timer) return
      this.retryTimer = undefined
      void this.acquire(trigger)
    }, delayMs)
    this.retryTimer = timer
  }

  private scheduleFailureRetry(): number {
    const delay = RETRY_DELAYS_MS[Math.min(this.retryIndex, RETRY_DELAYS_MS.length - 1)]!
    this.retryIndex = Math.min(this.retryIndex + 1, RETRY_DELAYS_MS.length - 1)
    this.scheduleRetry(delay, "retry")
    return delay
  }

  private attachChildExit(child: FrpChild, generation: number): void {
    const onExit = () => {
      if (!this.desired || generation !== this.generation || this.child !== child) return
      this.child = undefined
      this.state = { state: "connecting" }
      this.clearRenewTimer()
      this.clearAuditTimer()
      this.leaseExpiry = undefined
      this.renewalDueAt = undefined
      this.emit("warn", "relay_frpc_exited", { hostId: this.o.identity.hostId })
      this.scheduleRetry(1_000, "child_exit")
    }
    void child.exited.then(onExit, onExit)
  }

  private hasCurrentAttempt(): boolean {
    return this.activeAttempt?.generation === this.generation
  }

  private ownsAttempt(attempt: AcquireAttempt): boolean {
    return this.desired && attempt.generation === this.generation && this.activeAttempt === attempt
  }

  private async acquire(trigger: AcquireTrigger): Promise<void> {
    if (!this.desired || this.hasCurrentAttempt()) return
    const attempt = { generation: this.generation, token: Symbol("relay-acquire") }
    this.activeAttempt = attempt
    let failureCode: AcquireFailureCode = "nonce_failed"
    this.emit("info", "relay_lease_acquire_started", { trigger })
    const f = this.o.fetchImpl ?? fetch
    try {
      const nonce = await this.o.getNonce()
      if (!this.ownsAttempt(attempt)) return
      failureCode = "lease_request_failed"
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
      if (!this.ownsAttempt(attempt)) return
      if (!res.ok) throw new LeaseHttpError(res.status)
      failureCode = "lease_response_invalid"
      const body = (await res.json()) as { lease?: unknown; expiresAt?: unknown }
      if (!this.ownsAttempt(attempt)) return
      const lease = body.lease
      if (typeof lease !== "string" || lease.trim().length === 0) throw new Error("invalid lease response")
      const now = this.o.now?.() ?? Date.now()
      const expiresAt = body.expiresAt === undefined ? Number(lease.split(".")[1]) : body.expiresAt
      if (typeof expiresAt !== "number" || !Number.isFinite(expiresAt) || expiresAt <= now) {
        throw new Error("invalid lease expiry")
      }
      if (!this.ownsAttempt(attempt)) return
      const subdomain = `h-${this.o.identity.hostId}`
      const serverHost = new URL(this.o.relayBase).hostname
      const toml = [
        `serverAddr = ${JSON.stringify(serverHost)}`,
        "serverPort = 7000",
        "transport.tls.enable = true",
        "[metadatas]",
        `lease = ${JSON.stringify(lease)}`,
        "[[proxies]]",
        `name = ${JSON.stringify(`web-${this.o.identity.hostId}`)}`,
        'type = "http"',
        'localIP = "127.0.0.1"',
        `localPort = ${this.o.localPort}`,
        `subdomain = ${JSON.stringify(subdomain)}`,
        `metadatas.lease = ${JSON.stringify(lease)}`,
      ].join("\n")
      failureCode = "config_write_failed"
      const cfgPath = this.o.writeConfig(toml)
      if (!this.ownsAttempt(attempt)) return

      const previousChild = this.child
      failureCode = "frpc_spawn_failed"
      const child = this.o.spawn(["frpc", "-c", cfgPath])
      if (!this.ownsAttempt(attempt)) {
        try { child.kill() } catch { /* lifecycle already moved on */ }
        return
      }
      this.generation++
      const childGeneration = this.generation
      this.child = child
      try { previousChild?.kill() } catch { /* already gone */ }
      this.state = { state: "online", relayUrl: hostRelayUrl(this.o.identity.hostId, this.o.relayDomain) }
      this.leaseExpiry = expiresAt
      this.renewalDueAt = expiresAt - AUDIT_INTERVAL_MS
      this.retryIndex = 0
      this.clearRetryTimer()
      this.attachChildExit(child, childGeneration)
      const renewIn = Math.min(MAX_TIMER_DELAY_MS, Math.max(30_000, this.renewalDueAt - now))
      this.scheduleRenewal(renewIn)
      this.scheduleAudit()
      this.emit("info", "relay_lease_acquired", { hostId: this.o.identity.hostId, expiresAt, trigger })
      this.emit("info", "relay_frpc_started", { hostId: this.o.identity.hostId })
    } catch (e) {
      if (!this.ownsAttempt(attempt)) return
      const preservedChild = this.child !== undefined
      const error: AcquireFailureCode = e instanceof LeaseHttpError ? "lease_http_error" : failureCode
      if (!preservedChild) this.state = { state: "error", detail: error }
      const nextRetryMs = this.scheduleFailureRetry()
      const failure = e instanceof LeaseHttpError
        ? { trigger, error, status: e.status, preservedChild, nextRetryMs }
        : { trigger, error, preservedChild, nextRetryMs }
      this.emit("warn", "relay_lease_acquire_failed", failure)
    } finally {
      if (this.activeAttempt === attempt) this.activeAttempt = undefined
    }
  }

  async stop(): Promise<void> {
    this.desired = false
    this.generation++
    this.activeAttempt = undefined
    this.clearRenewTimer()
    this.clearAuditTimer()
    this.clearRetryTimer()
    this.leaseExpiry = undefined
    this.renewalDueAt = undefined
    this.retryIndex = 0
    try { this.child?.kill() } catch { /* already gone */ }
    this.child = undefined
    this.state = { state: "disabled" }
    this.emit("info", "relay_stopped", { hostId: this.o.identity.hostId })
  }
}
