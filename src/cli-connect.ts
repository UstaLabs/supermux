// CLI subcommand body for `supermux connect`.
// Kept here to leave src/cli.ts a thin dispatcher (mirrors src/cli-setup.ts).
//
// runConnectCommand:
//   - returns an exit code (0 ok, 1 soft failure, 2 usage) — never process.exit()
//   - all I/O is injectable (run/ask/confirm/println/providers/stateDir/tty) for tests
//
// Lifecycle per provider: detect → (install) → login → up → write .env + store →
// restart broker → re-pair. The tunnel runs as an OS-level service; the broker
// only ever learns its public face through MUX_WEB_PUBLIC_URL.

import { homedir } from "os"
import { join } from "path"
import { readFileSync, existsSync } from "fs"
import { createInterface } from "readline"
import { realRun, which } from "./core/tunnels/run"
import type { Ask, Confirm, ConnectCtx, Println, Run, TunnelProvider } from "./core/tunnels/types"
import { resolveMode } from "./core/tunnels/types"
import { PROVIDERS, getProvider } from "./core/tunnels/registry"
import {
  writeEnvPublicUrl,
  setEnvRelayDomain,
  setStorePublicUrl,
  restartBroker,
  mintPairLink,
  printPairLink,
} from "./core/tunnels/public-url"
import { openDb } from "./core/storage/db"
import { SettingsStore } from "./core/settings/store"
import type { TunnelRecord } from "./core/settings/app-config"
import { validateWebEnv } from "./shared/web-env"
import { loadOrCreateHostKey } from "./core/host-identity"

interface Flags {
  provider?: string
  status: boolean
  off: boolean
  switchTo?: string
  port?: string
  publicUrl?: string
  wildcard?: boolean
  wildcardDomain?: string
  mode?: string
  restart: boolean
  pair: boolean
  yes: boolean
  help: boolean
}

function parseFlags(args: string[]): Flags {
  const f: Flags = { status: false, off: false, restart: true, pair: true, yes: false, help: false }
  for (let i = 0; i < args.length; i++) {
    const a = args[i]
    switch (a) {
      case "--status": f.status = true; break
      case "--off": f.off = true; break
      case "--switch": f.switchTo = args[++i]; break
      case "--port": f.port = args[++i]; break
      case "--public-url": f.publicUrl = args[++i]; break
      case "--wildcard": f.wildcard = true; break
      case "--wildcard-domain": f.wildcardDomain = args[++i]; break
      case "--mode": f.mode = args[++i]; break
      case "--no-restart": f.restart = false; break
      case "--no-pair": f.pair = false; break
      case "--yes": case "-y": f.yes = true; break
      case "-h": case "--help": f.help = true; break
      default:
        if (a && a[0] !== "-" && !f.provider) f.provider = a
        // unknown flags are ignored so install.sh can pass extras
        break
    }
  }
  return f
}

/** Resolve the state dir from the CURRENT env (mirrors shared/paths.ts + cli-setup.ts). */
function resolveStateDir(): string {
  const home = process.env.HOME || homedir()
  const muxHome = process.env.MUX_HOME ?? join(home, ".mux")
  return process.env.MUX_STATE_DIR ?? join(muxHome, "state")
}

/** Port from --port, else MUX_WEB_PORT in .env, else 8787. */
function resolvePort(stateDir: string, flagPort?: string): string {
  if (flagPort) return flagPort
  try {
    const m = readFileSync(join(stateDir, ".env"), "utf8").match(/^MUX_WEB_PORT=(.*)$/m)
    if (m && m[1]) return m[1].trim()
  } catch {
    /* no .env yet */
  }
  return "8787"
}

/** Hosted relay domain from the setup-generated .env. Empty means explicitly disabled. */
function readHostedRelayDomain(stateDir: string): string | undefined {
  try {
    const m = readFileSync(join(stateDir, ".env"), "utf8").match(/^MUX_RELAY_DOMAIN=(.*)$/m)
    return m?.[1]?.trim() || undefined
  } catch {
    return undefined
  }
}

function hostedRelayUrl(stateDir: string, domain: string): string {
  const identity = loadOrCreateHostKey(join(stateDir, "host-key"))
  return `https://h-${identity.hostId}.${domain}`
}

/** Read the persisted tunnel record (best-effort). */
function readTunnelRecord(stateDir: string): TunnelRecord | undefined {
  const dbPath = join(stateDir, "db.sqlite3")
  if (!existsSync(dbPath)) return undefined
  let db
  try {
    db = openDb(dbPath)
    return new SettingsStore(db).getAppConfig({}).tunnel
  } catch {
    return undefined
  } finally {
    try { db?.close() } catch { /* ignore */ }
  }
}

const realAsk =
  (tty: boolean): Ask =>
  async (prompt) => {
    if (!tty) return null
    const rl = createInterface({ input: process.stdin, output: process.stdout })
    try {
      return await new Promise<string>((res) => rl.question(prompt, (a) => res(a)))
    } finally {
      rl.close()
    }
  }

const realConfirm =
  (tty: boolean, assumeYes: boolean, ask: Ask): Confirm =>
  async (prompt, def) => {
    if (assumeYes) return true
    if (!tty) return def
    const a = (await ask(`${prompt} [${def ? "Y/n" : "y/N"}] `)) ?? ""
    if (a === "") return def
    return /^y(es)?$/i.test(a.trim())
  }

const PROVIDER_BLURB: Record<string, string> = {
  cloudflared: "public https (named = stable; quick = throwaway)",
  tailscale: "your devices only (serve) — or public (funnel)",
  netbird: "private mesh (self-hostable)",
  ngrok: "public https (account; reserved domain = stable)",
  manual: "show me the port + proxy snippets",
}

function printMenu(providers: TunnelProvider[], println: Println): void {
  println("Reach supermux from outside this box?\n")
  providers.forEach((p, i) => {
    println(`  [${i + 1}] ${p.label.padEnd(20)} ${PROVIDER_BLURB[p.id] ?? ""}`)
  })
  println("  [0] Skip for now\n")
}

/** Run the full connect lifecycle for one provider. Returns an exit code. */
async function connectProvider(
  provider: TunnelProvider,
  ctx: ConnectCtx,
  flags: Flags,
): Promise<number> {
  // 1. detect + optional install
  if (provider.bin && !(await provider.detect(ctx))) {
    ctx.println(`→ ${provider.label}'s client isn't installed.`)
    if (!(await provider.install(ctx))) {
      ctx.println("Install the client, then re-run `supermux connect`.")
      return 1
    }
  }

  // 2. login
  ctx.println(`→ Authenticating with ${provider.label}…`)
  if (!(await provider.login(ctx))) {
    ctx.println("Login didn't complete — broker left on its current URL. Re-run when ready.")
    return 1
  }

  // 3. provision the tunnel
  ctx.println("→ Setting up the tunnel…")
  let result
  try {
    result = await provider.up(ctx)
  } catch (e) {
    ctx.println(`Tunnel setup failed: ${e instanceof Error ? e.message : String(e)}`)
    ctx.println("Nothing was changed — broker stays on its current URL.")
    return 1
  }

  // manual with no URL ⇒ informational guidance only (no write/restart)
  if (!result.publicUrl) {
    for (const n of result.notes ?? []) ctx.println(n)
    return 0
  }

  // 4. validate + persist (both surfaces)
  const v = validateWebEnv(ctx.port, result.publicUrl)
  if (!v.enabled) {
    ctx.println(`Refusing to write an invalid public URL (${v.error}). Broker unchanged.`)
    return 1
  }
  writeEnvPublicUrl(ctx.stateDir, ctx.port, result.publicUrl, result.proxyBaseDomain)
  setEnvRelayDomain(ctx.stateDir, "")
  setStorePublicUrl(ctx.stateDir, {
    webPublicUrl: result.publicUrl,
    webPort: ctx.port,
    tunnel: { provider: provider.id, mode: resolveMode(provider, ctx).id, publicUrl: result.publicUrl },
  })
  ctx.println(`\n✔ Public URL: ${result.publicUrl}`)
  if (!result.stable) ctx.println("⚠️  This URL is NOT stable — see the caveat above; an always-on box wants a stable option.")
  for (const n of result.notes ?? []) ctx.println(n)

  // 5. restart the broker so the new origin binds (CSRF + cookie scoping)
  if (flags.restart) await restartBroker(ctx.run, ctx.println)
  else ctx.println("Skipped broker restart (--no-restart) — restart it for the new URL to take effect.")

  // 6. re-pair (origin changed ⇒ old pairings are dead)
  if (flags.pair) {
    const link = mintPairLink(ctx.stateDir, "device", result.publicUrl)
    printPairLink(link, ctx.println)
  }
  return 0
}

/** `--off`: tear down the recorded tunnel and revert to localhost. */
async function disconnect(ctx: ConnectCtx, flags: Flags): Promise<number> {
  const rec = readTunnelRecord(ctx.stateDir)
  if (rec?.provider) {
    const p = getProvider(rec.provider)
    if (p) {
      ctx.println(`→ Tearing down ${p.label}…`)
      try { await p.down(ctx) } catch { /* best-effort */ }
    }
  }
  const local = `http://localhost:${ctx.port}`
  writeEnvPublicUrl(ctx.stateDir, ctx.port, local)
  setEnvRelayDomain(ctx.stateDir, "")
  setStorePublicUrl(ctx.stateDir, {
    webPublicUrl: local,
    webPort: ctx.port,
    tunnel: { provider: "", mode: "", publicUrl: "" },
  })
  ctx.println(`✔ Reverted to ${local}.`)
  if (flags.restart) await restartBroker(ctx.run, ctx.println)
  ctx.println("Devices now pair on the local origin again.")
  return 0
}

/** `--status`: report the current tunnel + a fresh pair link. */
function showStatus(ctx: ConnectCtx, pair = true): number {
  const rec = readTunnelRecord(ctx.stateDir)
  if (rec?.provider) {
    ctx.println(`Tunnel: ${rec.provider} (${rec.mode})`)
    ctx.println(`Public URL: ${rec.publicUrl}`)
    if (pair) printPairLink(mintPairLink(ctx.stateDir, "device", rec.publicUrl), ctx.println)
    return 0
  }

  const relayDomain = readHostedRelayDomain(ctx.stateDir)
  if (relayDomain) {
    const relayUrl = hostedRelayUrl(ctx.stateDir, relayDomain)
    ctx.println("Built-in Supermux relay: enabled")
    ctx.println(`Public URL: ${relayUrl}`)
    if (pair) printPairLink(mintPairLink(ctx.stateDir, "device", relayUrl), ctx.println)
    return 0
  }

  ctx.println("No remote connection configured — supermux is reachable on its local URL only.")
  ctx.println("Run `supermux setup` to enable the built-in relay, or `supermux connect <provider>` for your own tunnel.")
  return 0
}

const HELP = `supermux connect — set up a public/mesh link to this box

  supermux connect                 show the default relay URL + a fresh pair link
  supermux connect <provider>      cloudflared | tailscale | netbird | ngrok | manual
  supermux connect --status        show the current tunnel + a fresh pair link
  supermux connect --switch <p>    tear down the current tunnel, set up <p>
  supermux connect --off           disable remote connectivity and revert to localhost

Flags:
  --port <n>        web port (default: from .env, else 8787)
  --public-url <u>  set/override the public URL (cloudflared named host, ngrok domain, manual)
  --wildcard        (cloudflared named) also expose apps on *.<base> subdomains
  --wildcard-domain <d>  override the auto-derived wildcard base domain
  --mode <id>       provider mode (cloudflared: named|quick; tailscale: serve|funnel; ngrok: reserved|random)
  --no-restart      don't restart the broker after writing the URL
  --no-pair         don't print a new pairing link
  --yes, -y         assume "yes" to install/consent prompts (for install.sh / CI)
`

export interface ConnectDeps {
  run?: Run
  ask?: Ask
  confirm?: Confirm
  println?: Println
  providers?: TunnelProvider[]
  stateDir?: string
  tty?: boolean
}

export async function runConnectCommand(args: string[], deps: ConnectDeps = {}): Promise<number> {
  const flags = parseFlags(args)
  const println = deps.println ?? ((s: string) => console.log(s))
  if (flags.help) {
    println(HELP)
    return 0
  }

  const providers = deps.providers ?? PROVIDERS
  const stateDir = deps.stateDir ?? resolveStateDir()
  const tty = deps.tty ?? Boolean(process.stdin.isTTY)
  const port = resolvePort(stateDir, flags.port)
  const ask = deps.ask ?? realAsk(tty)
  const confirm = deps.confirm ?? realConfirm(tty, flags.yes, ask)
  const run = deps.run ?? realRun

  const ctx: ConnectCtx = {
    port,
    mode: flags.mode,
    stateDir,
    tty,
    yes: flags.yes,
    publicUrlHint: flags.publicUrl,
    wildcard: flags.wildcard,
    wildcardDomain: flags.wildcardDomain,
    run,
    println,
    ask,
    confirm,
  }

  // --status / --off first (no provider needed)
  if (flags.status) return showStatus(ctx, flags.pair)
  if (flags.off) return disconnect(ctx, flags)

  // Resolve which provider to set up
  let providerId = flags.switchTo ?? flags.provider
  if (!providerId) {
    if (readHostedRelayDomain(stateDir)) return showStatus(ctx, flags.pair)
    if (!tty) {
      println("No provider given and no terminal to show the menu.")
      println("Re-run with a provider, e.g.:  supermux connect cloudflared --yes")
      return 2
    }
    printMenu(providers, println)
    const choice = (await ask("Choice [1-5, 0 to skip]: ")) ?? "0"
    const idx = Number(choice.trim())
    if (!idx || idx < 1 || idx > providers.length) {
      println("Skipped — run `supermux connect` anytime.")
      return 0
    }
    providerId = providers[idx - 1]!.id
  }

  const provider = (deps.providers ? providers.find((p) => p.id === providerId) : getProvider(providerId))
  if (!provider) {
    println(`Unknown provider '${providerId}'. Choose: ${providers.map((p) => p.id).join(", ")}.`)
    return 2
  }

  // --switch: tear down the previous provider first (if different)
  if (flags.switchTo) {
    const rec = readTunnelRecord(stateDir)
    if (rec?.provider && rec.provider !== provider.id) {
      const old = getProvider(rec.provider)
      if (old) {
        println(`→ Switching from ${old.label} to ${provider.label}…`)
        try { await old.down(ctx) } catch { /* best-effort */ }
      }
    }
  }

  return connectProvider(provider, ctx, flags)
}
