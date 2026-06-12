import { readFileSync, writeFileSync, renameSync, existsSync, unlinkSync } from "fs"
import { makeLogger } from "../../shared/log"
import { home } from "../../shared/home"
import { shimSpawnSpec } from "./shim-spawn"

const log = makeLogger("trust")

// The shim is registered TWICE in ~/.claude.json: `mux-shim` is the tools provider
// (reply/spawn/…); `mux-channel` runs the same code CHANNEL-ONLY (MUX_CHANNEL_ONLY=1,
// zero tools) and is what `--dangerously-load-development-channels server:mux-channel`
// surfaces as the inbound channel. Splitting them means only ONE connection advertises
// tools, so an agent tool-call can't be dispatched twice. MUST match spawn-command.ts.
export const CLAUDE_SHIM_SERVER = "mux-shim"
export const CLAUDE_CHANNEL_SERVER = "mux-channel"

// Atomically prepare ~/.claude.json before launching claude for `workdir`.
// Several first-run concerns, one read-modify-write (avoids a concurrent-spawn
// clobber race). Each is a gate that, left unset on a fresh ~/.claude (notably
// Docker), leaves a headless-driven session stuck before it loads the inbound
// channel — messages then hang at "sending":
//
//   1. Pre-accept the folder-trust dialog. `--dangerously-skip-permissions`
//      does NOT bypass the "Accessing workspace" prompt for never-visited dirs;
//      we set hasTrustDialogAccepted=true directly.
//
//   2. Register the `mux-shim` MCP server. Claude resolves
//      `--dangerously-load-development-channels server:mux-shim` against
//      ~/.claude.json's mcpServers; without an entry the inbound channel never
//      loads and a fresh session sits at "sending" forever. The broker owns this
//      so it survives renames and needs no manual ~/.claude.json editing.
//
//   3. Skip the interactive first-run wizard (theme + login-method screen).
//   4. Pre-accept the "Bypass Permissions mode" disclaimer.
//
// Atomic: tmp file + rename — readers see old or new, never torn.
// Idempotent: writes only when something actually changed.
// Defensive: malformed claude.json is logged and left untouched.
export function preAcceptTrust(workdir: string): void {
  const path = `${home()}/.claude.json`

  let config: any
  if (existsSync(path)) {
    try {
      config = JSON.parse(readFileSync(path, "utf8"))
    } catch (err) {
      log.warn("malformed_claude_json_skipping_trust_preaccept", { path, err: String(err) })
      return
    }
  } else {
    config = {}
  }

  let changed = false

  // 1. Folder-trust pre-accept.
  config.projects ??= {}
  const existing = config.projects[workdir] ?? {}
  if (existing.hasTrustDialogAccepted !== true) {
    config.projects[workdir] = {
      ...existing,
      hasTrustDialogAccepted: true,
      allowedTools: existing.allowedTools ?? [],
      mcpServers: existing.mcpServers ?? {},
      enabledMcpjsonServers: existing.enabledMcpjsonServers ?? [],
      disabledMcpjsonServers: existing.disabledMcpjsonServers ?? [],
    }
    changed = true
  }

  // 2. Register the two shim MCP servers: tools (mux-shim) + channel-only
  //    (mux-channel, MUX_CHANNEL_ONLY=1). Only the tools one advertises tools.
  config.mcpServers ??= {}
  const spec = shimSpawnSpec()
  const desired: Record<string, { type: string; command: string; args: string[]; env: Record<string, string> }> = {
    [CLAUDE_SHIM_SERVER]: { type: "stdio", command: spec.shimCommand, args: spec.shimArgs, env: {} },
    [CLAUDE_CHANNEL_SERVER]: { type: "stdio", command: spec.shimCommand, args: spec.shimArgs, env: { MUX_CHANNEL_ONLY: "1" } },
  }
  for (const [name, want] of Object.entries(desired)) {
    const cur = config.mcpServers[name]
    const wired =
      cur && cur.command === want.command &&
      Array.isArray(cur.args) && JSON.stringify(cur.args) === JSON.stringify(want.args) &&
      (want.env.MUX_CHANNEL_ONLY ? cur.env?.MUX_CHANNEL_ONLY === "1" : !cur.env?.MUX_CHANNEL_ONLY)
    if (!wired) {
      config.mcpServers[name] = want
      changed = true
    }
  }

  // 3. Skip Claude's interactive first-run wizard (theme picker, then a login-method
  //    screen that forces a fresh OAuth re-login EVEN WHEN valid creds exist). The
  //    broker drives claude headlessly, so on a fresh ~/.claude (notably Docker) that
  //    wizard leaves the session stuck before it ever loads the inbound channel —
  //    messages then hang at "sending". Marking onboarding complete makes claude use
  //    the existing credentials and go straight to its prompt.
  if (config.hasCompletedOnboarding !== true) {
    config.hasCompletedOnboarding = true
    changed = true
  }
  if (!config.theme) {
    config.theme = "dark"
    changed = true
  }

  // 4. Pre-accept the "Bypass Permissions mode" disclaimer. The broker always
  //    spawns claude with --dangerously-skip-permissions; on a fresh ~/.claude
  //    (notably Docker) that triggers a blocking warning whose pre-selected
  //    option is "No, exit" — so the single Enter the broker sends to clear the
  //    dev-channels consent would instead QUIT claude before the channel loads,
  //    leaving the session stuck at "sending". Claude reads this from the
  //    top-level config (h$().bypassPermissionsModeAccepted); setting it sends
  //    claude straight to the dev-channels consent (which the broker's Enter
  //    clears), then to its working prompt where the inbound channel registers.
  if (config.bypassPermissionsModeAccepted !== true) {
    config.bypassPermissionsModeAccepted = true
    changed = true
  }

  if (!changed) return

  const tmp = `${path}.tmp.${process.pid}`
  try {
    writeFileSync(tmp, JSON.stringify(config, null, 2))
    renameSync(tmp, path)
    log.info("trust_preaccepted", { workdir })
  } catch (err) {
    try { unlinkSync(tmp) } catch {}
    log.error("trust_preaccept_failed", { workdir, err: String(err) })
  }
}
