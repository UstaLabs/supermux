// One-time, idempotent migration from the legacy `agentmux` layout to the
// neutral `mux` layout (project 1/7 of the OSS cleanup — see
// docs/superpowers/specs/2026-05-31-supermux-rename-design.md).
//
// What it does (clean break):
//   1. Move ~/.agentmux  →  ~/.mux   (rename preserves the .git knowledge repo)
//   2. Rewrite ~/.mux/state/.env keys to the MUX_ prefix (backs up to .env.bak)
//   3. Rename the first-party plugin dir agentmux-core → mux-core and patch its
//      manifests + plugins.json
//   4. Print (does NOT run) the manual systemctl / git steps for the operator.
//
// Safe: refuses if ~/.mux already exists; --dry-run reports the plan and touches
// nothing. The actual code rename ships separately via the normal PR.
//
// Usage:  bun scripts/migrate-to-mux.ts [--dry-run]

import { existsSync, renameSync, readFileSync, writeFileSync, copyFileSync, readdirSync } from "fs"
import { join } from "path"
import { homedir } from "os"

// Env keys that gain the MUX_ prefix. Provider-conventional keys
// (OPENAI_API_KEY, CURSOR_API_KEY, CLAUDE_SESSION_ID) are intentionally absent.
const ENV_PREFIX_AS_IS = ["WEB_PORT", "WEB_PUBLIC_URL", "WEB_VAPID_SUBJECT", "WEB_UPLOAD_MAX_MB", "PROXY_BASE_DOMAIN", "TELEGRAM_BOT_TOKEN"]
const CURATOR_KEYS = ["CURATOR_ENABLED", "CURATOR_HOUR", "CURATOR_RUN_NOW", "CURATOR_CHAT_ID"]

export interface MigratePlan {
  steps: string[]
}

export interface MigrateOpts {
  /** Home directory to operate in (defaults to the real $HOME). Tests inject a temp dir. */
  home?: string
  dryRun?: boolean
}

/** Rewrite a single .env line's KEY to the MUX_ form, leaving VALUE untouched. */
export function renameEnvKey(line: string): string {
  const m = line.match(/^(\s*)([A-Z0-9_]+)(\s*=.*)$/)
  if (!m) return line
  const [, lead, key, rest] = m
  if (key!.startsWith("MUX_")) return line
  if (key!.startsWith("AGENTMUX_")) return `${lead}MUX_${key!.slice("AGENTMUX_".length)}${rest}`
  if (ENV_PREFIX_AS_IS.includes(key!) || CURATOR_KEYS.includes(key!)) return `${lead}MUX_${key}${rest}`
  return line // provider-conventional / unknown keys stay as-is
}

export function migrate(opts: MigrateOpts = {}): MigratePlan {
  const home = opts.home ?? homedir()
  const dryRun = opts.dryRun ?? false
  const legacy = join(home, ".agentmux")
  const target = join(home, ".mux")
  const steps: string[] = []

  if (existsSync(target)) {
    throw new Error(`~/.mux already exists at ${target} — refusing to overwrite. Reconcile manually.`)
  }
  if (!existsSync(legacy)) {
    steps.push(`No legacy ~/.agentmux found at ${legacy} — nothing to move.`)
  } else {
    steps.push(`Move ${legacy} → ${target} (preserves the .git knowledge repo)`)
  }

  const envPath = join(target, "state", ".env")
  steps.push(`Rewrite ${envPath} keys to MUX_ (backup → .env.bak)`)

  const coreLegacy = join(target, "plugins", "agentmux-core")
  const coreNew = join(target, "plugins", "mux-core")
  steps.push(`Rename plugin dir ${coreLegacy} → ${coreNew}; set manifest name "agentmux" → "mux"; patch plugins.json`)

  steps.push("MANUAL (printed, not run): install systemd/mux.service, then:")
  steps.push("  systemctl --user disable --now claudemux.service 2>/dev/null || true")
  steps.push("  systemctl --user disable --now agentmux.service 2>/dev/null || true")
  steps.push("  systemctl --user enable --now mux.service")
  steps.push("MANUAL: respawn all sessions (the MCP server is now mux-shim).")
  steps.push("MANUAL (optional): rename your GitHub repos + ~/projects clone dir.")

  if (dryRun) return { steps }

  // 1. Move the state/knowledge dir.
  if (existsSync(legacy)) renameSync(legacy, target)

  // 2. Rewrite .env keys.
  if (existsSync(envPath)) {
    const original = readFileSync(envPath, "utf8")
    copyFileSync(envPath, `${envPath}.bak`)
    const rewritten = original.split("\n").map(renameEnvKey).join("\n")
    writeFileSync(envPath, rewritten)
  }

  // 3. Rename the plugin dir + patch manifests + registry.
  if (existsSync(coreLegacy) && !existsSync(coreNew)) {
    renameSync(coreLegacy, coreNew)
    for (const sub of [".claude-plugin", ".codex-plugin", ".cursor-plugin"]) {
      const mf = join(coreNew, sub, "plugin.json")
      if (existsSync(mf)) {
        const j = JSON.parse(readFileSync(mf, "utf8"))
        if (j.name === "agentmux") j.name = "mux"
        if (typeof j.displayName === "string") j.displayName = j.displayName.replace(/agentmux/gi, "mux")
        if (typeof j.description === "string") j.description = j.description.replace(/agentmux/gi, "supermux")
        if (Array.isArray(j.keywords)) j.keywords = j.keywords.map((k: string) => (k === "agentmux" ? "mux" : k))
        if (j.author?.name === "agentmux") j.author.name = "mux"
        writeFileSync(mf, JSON.stringify(j, null, 2) + "\n")
      }
    }
  }
  const registry = join(target, "plugins.json")
  if (existsSync(registry)) {
    let txt = readFileSync(registry, "utf8")
    txt = txt.replace(/agentmux-core/g, "mux-core").replace(/\.agentmux\/plugins/g, ".mux/plugins")
    writeFileSync(registry, txt)
  }

  return { steps }
}

if (import.meta.main) {
  const dryRun = process.argv.includes("--dry-run")
  const plan = migrate({ dryRun })
  console.log(dryRun ? "DRY RUN — no changes made.\n" : "Migration complete.\n")
  for (const s of plan.steps) console.log("•", s)
  // Surface any plugin dir left behind for the operator's awareness.
  const pluginsDir = join(homedir(), ".mux", "plugins")
  if (!dryRun && existsSync(pluginsDir)) {
    console.log("\nPlugins now under ~/.mux/plugins:", readdirSync(pluginsDir).join(", "))
  }
}
