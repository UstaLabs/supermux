// Tunnel-provider contract for `supermux connect`. Every provider implements
// TunnelProvider; the orchestrator (cli-connect.ts) drives the shared lifecycle.
// ALL external process calls go through the injected `Run` so the whole feature
// is unit-testable with zero network.

/** Result of one spawned process. */
export interface RunResult {
  code: number
  stdout: string
  stderr: string
}

/**
 * Spawn a process. Injected everywhere so tests can fake it (no real network).
 *
 * `stream: true` tees the child's stdout/stderr to this process's stdout/stderr
 * live (instead of buffering until exit) and inherits stdin — required for any
 * INTERACTIVE step (e.g. `tailscale up` prints an auth URL then blocks on the
 * browser). Without it the URL stays buffered and the command looks frozen.
 */
export type Run = (
  argv: string[],
  opts?: { input?: string; timeoutMs?: number; stream?: boolean },
) => Promise<RunResult>

/** Output sink (defaults to console.log in production; captured in tests). */
export type Println = (s: string) => void

/** Ask the user a free-text answer (e.g. paste an authtoken). Null when no TTY/declined. */
export type Ask = (prompt: string) => Promise<string | null>

/** Yes/no consent (no TTY ⇒ resolves to `def`). */
export type Confirm = (prompt: string, def: boolean) => Promise<boolean>

/** Everything a provider needs, all injectable. */
export interface ConnectCtx {
  port: string // e.g. "8787"
  mode?: string // provider-specific mode id; undefined ⇒ default (modes[0])
  stateDir: string // resolved MUX state dir
  tty: boolean // is a controlling TTY available?
  yes: boolean // --yes: assume consent, never prompt
  publicUrlHint?: string // a user-supplied --public-url / host (cloudflared named, ngrok reserved)
  run: Run
  println: Println
  ask: Ask
  confirm: Confirm
}

export interface TunnelMode {
  id: string // "named" | "quick" | "serve" | "funnel" | "reserved" | "random" | "mesh"
  label: string
  stable: boolean // false ⇒ ephemeral; carries the ⚠️ caveat, never the default
}

export interface TunnelResult {
  publicUrl: string // the https URL to set as MUX_WEB_PUBLIC_URL
  stable: boolean // false ⇒ print the throwaway caveat
  notes?: string[] // extra lines for the user (e.g. "device must run Tailscale")
}

export interface TunnelProvider {
  id: "cloudflared" | "tailscale" | "netbird" | "ngrok" | "manual"
  label: string
  /** Modes in priority order; modes[0] is the default and MUST be stable (except `manual`). */
  modes: TunnelMode[]
  /** The PATH binary to look for (undefined for `manual`). */
  bin?: string
  /** Is the client installed? */
  detect(ctx: ConnectCtx): Promise<boolean>
  /** Offer to install the client (consent-gated). Return false if declined/failed. */
  install(ctx: ConnectCtx): Promise<boolean>
  /** Authenticate (browser SSO / token paste). Return false on failure. */
  login(ctx: ConnectCtx): Promise<boolean>
  /** Provision a PERSISTENT tunnel → 127.0.0.1:ctx.port and return the public URL. */
  up(ctx: ConnectCtx): Promise<TunnelResult>
  /** Tear down the persistent tunnel/service for this provider. Idempotent. */
  down(ctx: ConnectCtx): Promise<void>
  /** Report current state (best-effort). */
  status(ctx: ConnectCtx): Promise<{ up: boolean; url?: string }>
}

/** Resolve the mode a provider should use from ctx (explicit --mode, else default). */
export function resolveMode(provider: TunnelProvider, ctx: ConnectCtx): TunnelMode {
  if (ctx.mode) {
    const m = provider.modes.find((x) => x.id === ctx.mode)
    if (m) return m
  }
  return provider.modes[0]!
}
