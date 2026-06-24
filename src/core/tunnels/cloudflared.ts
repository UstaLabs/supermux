// Cloudflare Tunnel provider for `supermux connect`.
//
// Two modes (see types.ts → TunnelMode; order matters, modes[0] is the default):
//   • named — a stable https://<host> backed by a persistent service. Needs a CF
//     account + a domain ON Cloudflare. This is the default; it survives reboots.
//   • quick — a throwaway *.trycloudflare.com URL (no account). Changes every
//     restart ⇒ you re-pair; never advertised as "your link" (carries a ⚠️ note).
//
// Every external process call goes through `ctx.run([...argv])` so the whole
// provider is unit-testable with a faked Run — NO Bun.spawn, NO network here.
// URLs are scraped from CLI output with `extractFirstUrl`.

import { homedir } from "os"
import { which, extractFirstUrl } from "./run"
import { resolveMode, type TunnelProvider, type ConnectCtx, type TunnelResult } from "./types"

const TUNNEL_NAME = "supermux"
// Working, self-updating install pages. The old
// developers.cloudflare.com/cloudflare-tunnel/downloads/ path now 404s.
// pkg.cloudflare.com carries the apt/dnf repo instructions; the releases page has
// standalone binaries for any distro/arch.
const DOWNLOAD_DOCS = "https://pkg.cloudflare.com/"
const RELEASES = "https://github.com/cloudflare/cloudflared/releases/latest"

/**
 * Reduce `ctx.publicUrlHint` to a bare hostname for the named mode. Accepts a
 * full URL ("https://mux.example.com/x" → "mux.example.com") or an already-bare
 * host ("mux.example.com" → "mux.example.com"). Returns undefined when empty so
 * up() knows to prompt.
 */
function hostFromHint(hint?: string): string | undefined {
  if (!hint) return undefined
  const trimmed = hint.trim()
  if (!trimmed) return undefined
  try {
    // Has a scheme? Let URL pull the hostname out cleanly.
    if (/^[a-z][a-z0-9+.-]*:\/\//i.test(trimmed)) return new URL(trimmed).hostname
  } catch {
    // Fall through and treat it as a bare host below.
  }
  // Bare host (maybe with a path/port) — strip anything after the authority.
  return trimmed.replace(/^\/\//, "").split(/[/:?#]/)[0] || undefined
}

/**
 * Derive the wildcard base domain from a tunnel host by dropping the leftmost
 * label: "mux.example.com" → "example.com". A bare apex (≤2 labels) is returned
 * unchanged. Public-suffix edge cases (e.g. "x.example.co.uk") are why the caller
 * shows this for confirmation / allows --wildcard-domain to override it.
 */
export function baseDomainOf(host: string): string {
  const labels = host.split(".").filter(Boolean)
  return labels.length <= 2 ? host : labels.slice(1).join(".")
}

/**
 * Build cloudflared's config.yml for the named tunnel. The broker-host ingress
 * rule is the line the old flow omitted (→ the 404). A wildcard rule is added when
 * `wildcardBase` is set. The leading marker lets a re-run recognize a
 * supermux-written file (so it isn't backed up again). Falls back to the tunnel
 * NAME and omits credentials-file when the UUID couldn't be resolved — cloudflared
 * then locates the credentials by name in its default dir.
 */
export function buildTunnelConfig(opts: {
  tunnelId?: string
  credentialsFile?: string
  port: string
  host: string
  wildcardBase?: string
}): string {
  const svc = `http://localhost:${opts.port}`
  const rules = [`  - hostname: ${opts.host}\n    service: ${svc}`]
  if (opts.wildcardBase) rules.push(`  - hostname: "*.${opts.wildcardBase}"\n    service: ${svc}`)
  rules.push(`  - service: http_status:404`)
  const creds = opts.credentialsFile ? `credentials-file: ${opts.credentialsFile}\n` : ""
  return (
    `# Managed by supermux connect — re-running may overwrite this file.\n` +
    `tunnel: ${opts.tunnelId || TUNNEL_NAME}\n` +
    creds +
    `ingress:\n${rules.join("\n")}\n`
  )
}

/** Pull a tunnel UUID out of `cloudflared tunnel create` stdout (or any text). */
export function parseTunnelId(text: string): string | undefined {
  return text.match(/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/i)?.[0]
}

/**
 * The shell script that installs cloudflared from Cloudflare's official package
 * repo (https://pkg.cloudflare.com) for a detected package manager. Uses sudo only
 * when not already root. apt adds the signed repo + key; dnf/yum drop the .repo
 * file. Installs non-interactively (-y). Returned for `sh -c`.
 */
export function linuxInstallScript(pm: "apt" | "dnf" | "yum"): string {
  const sudo = `SUDO=""; [ "$(id -u)" = 0 ] || SUDO=sudo;`
  if (pm === "apt") {
    return (
      `${sudo} ` +
      `$SUDO mkdir -p --mode=0755 /usr/share/keyrings && ` +
      `curl -fsSL https://pkg.cloudflare.com/cloudflare-main.gpg | $SUDO tee /usr/share/keyrings/cloudflare-main.gpg >/dev/null && ` +
      `echo 'deb [signed-by=/usr/share/keyrings/cloudflare-main.gpg] https://pkg.cloudflare.com/cloudflared any main' | $SUDO tee /etc/apt/sources.list.d/cloudflared.list >/dev/null && ` +
      `$SUDO apt-get update && $SUDO apt-get install -y cloudflared`
    )
  }
  // dnf / yum (RPM): drop Cloudflare's .repo file, then install.
  return (
    `${sudo} ` +
    `curl -fsSL https://pkg.cloudflare.com/cloudflared.repo | $SUDO tee /etc/yum.repos.d/cloudflared.repo >/dev/null && ` +
    `$SUDO ${pm} install -y cloudflared`
  )
}

/** Copy-paste fallback when auto-install can't run (unknown PM, no curl, sudo denied). */
export function installHintLines(): string[] {
  return [
    "Couldn't auto-install cloudflared. Install it manually, then re-run `supermux connect`:",
    `  • Packages (apt/dnf): ${DOWNLOAD_DOCS}`,
    `  • Standalone binary:  ${RELEASES}`,
  ]
}

/**
 * Conservative hostname guard: letters, digits, dots, hyphens only. Rejects
 * whitespace/newlines/shell metacharacters so a host or base domain can be safely
 * interpolated into the config heredoc and DNS-route argv. Throws on a bad value.
 */
function assertHostname(kind: string, value: string): void {
  if (!/^[a-z0-9.-]+$/i.test(value)) throw new Error(`invalid ${kind}: ${JSON.stringify(value)}`)
}

/**
 * Best-effort tunnel UUID: prefer the id printed by `tunnel create`; on a re-run
 * ("already exists" ⇒ no id printed) fall back to `tunnel list --output json`.
 * Returns undefined if neither yields one (caller then writes config by name).
 */
async function resolveTunnelId(ctx: ConnectCtx, createOut: string): Promise<string | undefined> {
  const fromCreate = parseTunnelId(createOut)
  if (fromCreate) return fromCreate
  const r = await ctx.run(["cloudflared", "tunnel", "list", "--output", "json"])
  try {
    const list = JSON.parse(r.stdout) as Array<{ id?: string; name?: string }>
    return list.find((t) => t.name === TUNNEL_NAME)?.id
  } catch {
    return undefined
  }
}

export const cloudflaredProvider: TunnelProvider = {
  id: "cloudflared",
  label: "Cloudflare Tunnel",
  bin: "cloudflared",
  modes: [
    { id: "named", label: "Named tunnel (stable; needs a domain on Cloudflare)", stable: true },
    { id: "quick", label: "Quick tunnel (throwaway URL)", stable: false },
  ],

  /** Client on PATH? */
  async detect(_ctx: ConnectCtx): Promise<boolean> {
    return which("cloudflared")
  },

  /**
   * Offer to install cloudflared (consent-gated). Best-effort, never throws:
   *   • darwin → `brew install cloudflared`
   *   • linux  → Cloudflare's official apt/dnf/yum repo (sudo when not root)
   * Falls back to printing working install links. Returns true iff cloudflared
   * ends up on PATH.
   */
  async install(ctx: ConnectCtx): Promise<boolean> {
    // Already there? Nothing to do.
    if (which("cloudflared")) return true

    // Consent. With --yes we assume yes and never prompt (install.sh / CI).
    const ok = ctx.yes || (await ctx.confirm("Install cloudflared? (Cloudflare Tunnel client)", true))
    if (!ok) return false

    if (process.platform === "darwin") {
      try {
        await ctx.run(["brew", "install", "cloudflared"])
      } catch {
        // Swallow — we re-check PATH below; brew may be absent or offline.
      }
    } else {
      // Linux: install from Cloudflare's official repo via the detected package
      // manager (best-effort; needs sudo unless already root).
      const pm = which("apt-get") ? "apt" : which("dnf") ? "dnf" : which("yum") ? "yum" : undefined
      if (pm) {
        ctx.println(`Installing cloudflared via ${pm} (Cloudflare's official repo)…`)
        try {
          await ctx.run(["sh", "-c", linuxInstallScript(pm)], { stream: true })
        } catch {
          // Swallow — re-check PATH below; print the fallback if it didn't land.
        }
      }
    }

    if (which("cloudflared")) return true

    // Auto-install didn't land — print working, copy-paste instructions.
    for (const line of installHintLines()) ctx.println(line)
    return false
  },

  /**
   * Authenticate. Only the *named* mode needs a Cloudflare login (it provisions
   * DNS + a credentialed tunnel). `cloudflared tunnel login` opens a browser /
   * prints a URL. Quick mode is anonymous → no login.
   */
  async login(ctx: ConnectCtx): Promise<boolean> {
    if (resolveMode(this, ctx).id !== "named") return true
    // Stream live: `tunnel login` opens/prints a browser auth URL and waits — a
    // buffered capture would hide it until exit, making the step look frozen.
    const r = await ctx.run(["cloudflared", "tunnel", "login"], { stream: true })
    return r.code === 0
  },

  /** Provision the tunnel for the resolved mode and return its public URL. */
  async up(ctx: ConnectCtx): Promise<TunnelResult> {
    const mode = resolveMode(this, ctx)

    if (mode.id === "named") {
      // Hostname: prefer the user-supplied hint, else ask. (No TTY ⇒ ask ⇒ null.)
      const host =
        hostFromHint(ctx.publicUrlHint) ??
        hostFromHint((await ctx.ask("Hostname for the tunnel (e.g. mux.yourdomain.com): ")) ?? undefined)
      if (!host) throw new Error("a hostname is required for a named cloudflared tunnel")
      assertHostname("hostname", host)

      // 1. Create the tunnel (tolerate a re-run) and learn its UUID (best-effort).
      const created = await ctx.run(["cloudflared", "tunnel", "create", TUNNEL_NAME])
      if (created.code !== 0 && !/already exists/i.test(created.stdout + created.stderr)) {
        throw new Error(`cloudflared tunnel create failed: ${created.stderr || created.stdout}`)
      }
      const tunnelId = await resolveTunnelId(ctx, created.stdout)

      // 2. Decide wildcard subdomains for exposed apps. --wildcard forces it on;
      //    otherwise prompt only when interactive (never under --yes / no-TTY).
      const base = ctx.wildcardDomain || baseDomainOf(host)
      let wildcardBase: string | undefined
      if (ctx.wildcard === true) {
        wildcardBase = base
      } else if (ctx.wildcard === undefined && ctx.tty && !ctx.yes) {
        if (await ctx.confirm(`Also expose apps on their own subdomains under *.${base}? `, false)) {
          wildcardBase = base
        }
      }
      if (wildcardBase) assertHostname("wildcard base domain", wildcardBase)

      // 3. Route DNS for the broker host, plus the wildcard when requested. A
      //    wildcard-DNS failure (plan limits) must NOT break the broker host — drop
      //    back to path mode and clear the base domain.
      await ctx.run(["cloudflared", "tunnel", "route", "dns", TUNNEL_NAME, host])
      if (wildcardBase) {
        const wr = await ctx.run(["cloudflared", "tunnel", "route", "dns", TUNNEL_NAME, `*.${wildcardBase}`])
        if (wr.code !== 0) {
          ctx.println(
            `Wildcard DNS (*.${wildcardBase}) failed: ${wr.stderr || wr.stdout}. ` +
              `Exposed apps stay on path mode (/p/<slug>/); the broker UI is unaffected.`,
          )
          wildcardBase = undefined
        }
      }

      // 4. Write the ingress config (the rule the old flow omitted → the 404). Back
      //    up a pre-existing NON-supermux config.yml before overwriting it.
      const home = process.env.HOME || homedir()
      const cfgDir = `${home}/.cloudflared`
      const cfgPath = `${cfgDir}/config.yml`
      const yaml = buildTunnelConfig({
        tunnelId,
        credentialsFile: tunnelId ? `${cfgDir}/${tunnelId}.json` : undefined,
        port: ctx.port,
        host,
        wildcardBase,
      })
      const wrote = await ctx.run([
        "sh",
        "-c",
        `mkdir -p "${cfgDir}"; ` +
          `if [ -f "${cfgPath}" ] && ! grep -q "Managed by supermux" "${cfgPath}"; then cp "${cfgPath}" "${cfgPath}.bak"; fi; ` +
          `cat > "${cfgPath}" <<'SUPERMUX_CFG'\n${yaml}SUPERMUX_CFG\n`,
      ])
      if (wrote.code !== 0) {
        ctx.println(`Couldn't write ${cfgPath}: ${wrote.stderr || wrote.stdout}. The host may keep returning 404 until the ingress config exists.`)
      }

      // 5. Install it as a persistent OS service (reads the config above). Best-
      //    effort: on failure, hand the user the manual run command.
      const svc = await ctx.run(["cloudflared", "service", "install"])
      if (svc.code !== 0) {
        ctx.println("Couldn't install cloudflared as a service. Run it yourself (keep it running):")
        ctx.println(`  cloudflared tunnel run ${TUNNEL_NAME}`)
      }

      const result: TunnelResult = { publicUrl: `https://${host}`, stable: true }
      if (wildcardBase) result.proxyBaseDomain = wildcardBase
      return result
    }

    // quick: an anonymous, PERSISTENT tunnel. We must NOT scrape-then-kill — that
    // kills the very tunnel we need. Launch it DETACHED (nohup ⇒ survives this
    // short-lived CLI, reparented to init), log to a file, record its PID for
    // down(), then poll the log for the *.trycloudflare.com URL it prints.
    const log = `${ctx.stateDir}/cloudflared-quick.log`
    const pidFile = `${ctx.stateDir}/cloudflared-quick.pid`
    await ctx.run([
      "sh",
      "-c",
      `mkdir -p "${ctx.stateDir}"; : > "${log}"; ` +
        `nohup cloudflared tunnel --no-autoupdate --url http://localhost:${ctx.port} > "${log}" 2>&1 < /dev/null & ` +
        `echo $! > "${pidFile}"`,
    ])

    // Poll the log for the URL (cloudflared reports it within a few seconds).
    let url: string | undefined
    for (let i = 0; i < 20; i++) {
      const r = await ctx.run(["sh", "-c", `cat "${log}" 2>/dev/null`])
      url = extractFirstUrl(r.stdout, /trycloudflare\.com/)
      if (url) break
      await Bun.sleep(1000)
    }
    if (!url) {
      throw new Error("could not get a quick-tunnel URL (cloudflared didn't report one in time)")
    }

    return {
      publicUrl: url,
      stable: false,
      notes: [
        "⚠️ Throwaway URL — it changes every restart and you'll re-pair. Not for an always-on box.",
        `Tunnel runs in the background (pid in ${pidFile}); stop it with \`supermux connect --off\`.`,
      ],
    }
  },

  /**
   * Tear down the persistent tunnel + service. Idempotent and best-effort: both
   * steps are safe to run when nothing exists, and any error is swallowed.
   */
  async down(ctx: ConnectCtx): Promise<void> {
    // Stop a detached quick tunnel if we started one (kill the recorded PID).
    try {
      const pidFile = `${ctx.stateDir}/cloudflared-quick.pid`
      await ctx.run([
        "sh",
        "-c",
        `[ -f "${pidFile}" ] && kill "$(cat "${pidFile}")" 2>/dev/null; rm -f "${pidFile}"`,
      ])
    } catch {
      // nothing to stop
    }
    try {
      await ctx.run(["cloudflared", "service", "uninstall"])
    } catch {
      // not installed / already gone
    }
    try {
      await ctx.run(["cloudflared", "tunnel", "cleanup", TUNNEL_NAME])
    } catch {
      // no such tunnel / nothing to clean
    }
  },

  /** Up iff `cloudflared tunnel list` succeeds and mentions our tunnel. */
  async status(ctx: ConnectCtx): Promise<{ up: boolean; url?: string }> {
    const r = await ctx.run(["cloudflared", "tunnel", "list"])
    return { up: r.code === 0 && new RegExp(TUNNEL_NAME).test(r.stdout) }
  },
}
