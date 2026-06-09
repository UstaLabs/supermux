import { resolve, basename, join } from "path"
import { writeFileSync, mkdirSync, existsSync } from "fs"
import { buildMemoryPreamble } from "../memory/preamble"
import { ENVIRONMENT_MD_PATH } from "../agents/environment"
import type { AgentRole } from "../memory/injector"
import type { SessionRole } from "./policy"
import { STATE_DIR } from "../../shared/paths"
import { CLAUDE_HOOKS_SETTINGS_PATH } from "../agents/claude/hooks-settings"
import { claudeSpawnArgs, codexSpawnArgs, cursorSpawnArgs } from "../plugins"
import { makeLogger } from "../../shared/log"

const log = makeLogger("spawn-command")

// spawn-command.ts lives at src/core/session-manager/, so the project root is
// three levels up. (This file was at src/broker/ before Phase 1's refactor;
// the relative depth changed when it moved.)
const PROMPTS_DIR = resolve(import.meta.dirname, "..", "..", "..", "prompts")

// The reply conventions now live in the mux-core plugin's SessionStart
// hook (see the plugin-host design spec, Phase 4).
// This file is appended ONLY as a safety net when that plugin is absent from
// the spawn (e.g. a missing/broken plugins.json), so a session can never end up
// with no reply rules at all.
const REPLY_FALLBACK_PATH = resolve(PROMPTS_DIR, "reply-fallback.md")
const CORE_PLUGIN_NAME = "mux-core"

// Single source of truth for the claude invocation broker spawns into a tmux
// pane. Both supervisor.ts (personal assistants) and spawn-helper.ts
// (user-requested workers) use this. Adding/removing flags later is one edit,
// not two.
export function buildClaudeSpawnCommand(opts: { name: string; sessionId?: string; model?: string; effort?: string; role?: AgentRole; sessionRole?: SessionRole; claudeSessionId?: string; resume?: boolean; pluginsFile?: string; pluginsDir?: string; workdir?: string }): string {
  const role: AgentRole = opts.role ?? (opts.sessionRole === "personal_assistant" ? "main" : "worker")
  const memoryPreamblePath = writeSessionMemoryPreamble(opts.name, role, opts.workdir)
  const modelFlag = opts.model ? ` --model ${opts.model}` : ""
  const effortFlag = opts.effort ? ` --effort ${opts.effort}` : ""
  const sessionId = opts.sessionId ?? opts.name
  let sessionFlag = ""
  if (opts.claudeSessionId) {
    sessionFlag = opts.resume ? ` --resume ${opts.claudeSessionId}` : ` --session-id ${opts.claudeSessionId}`
  }
  const settingsFlag = existsSync(CLAUDE_HOOKS_SETTINGS_PATH) ? ` --settings ${CLAUDE_HOOKS_SETTINGS_PATH}` : ""
  const { flags: pluginFlags, hasCorePlugin } = buildPluginFlags(opts.name, opts.pluginsFile, opts.pluginsDir)
  // Reply rules normally arrive via mux-core's SessionStart hook. Only
  // append the static fallback when that plugin isn't part of this spawn.
  const replyFallback = hasCorePlugin ? "" : ` --append-system-prompt-file ${REPLY_FALLBACK_PATH}`
  // MUX_SESSION_ROLE lets the SessionStart hook inject the PA's soul.md (its full
  // identity) for personal assistants only — workers must never receive soul.md.
  return `bash -lc 'CLAUDE_CODE_DISABLE_AUTO_MEMORY=1 MUX_SESSION_ID=${sessionId} MUX_DISPLAY_NAME=${opts.name} MUX_SESSION_ROLE=${role} ` +
    `claude --dangerously-skip-permissions --dangerously-load-development-channels server:mux-channel ` +
    `--add-dir ${PROMPTS_DIR} ` +
    `--append-system-prompt-file ${ENVIRONMENT_MD_PATH} ` +
    `--append-system-prompt-file ${memoryPreamblePath}${replyFallback}${pluginFlags}${modelFlag}${effortFlag}${sessionFlag}${settingsFlag}'`
}

// Single source of truth for the codex invocation broker spawns into a tmux
// pane (or supervisor). Follows the same env-var + bash -lc pattern as claude.
export function buildCodexSpawnCommand(opts: { name: string; sessionId?: string; model?: string; effort?: string; codexHome?: string; workdir?: string; pluginsFile?: string; pluginsDir?: string }): string {
  const sessionId = opts.sessionId ?? opts.name
  const codexHome = opts.codexHome ?? join(STATE_DIR, "agents", "codex", opts.name)
  const modelFlag = opts.model ? ` -c model="${opts.model}"` : ""
  const effortFlag = opts.effort ? ` -c model_reasoning_effort="${opts.effort}"` : ""
  const { args: pluginArgs } = codexSpawnArgs({ sessionName: opts.name, file: opts.pluginsFile, pluginsDir: opts.pluginsDir, onError: (msg) => log.warn("plugins_registry_invalid", { err: msg }) })
  const pluginFlags = pluginArgs.length ? ` ${pluginArgs.join(" ")}` : ""
  return `bash -lc 'CODEX_HOME=${codexHome} MUX_SESSION_ID=${sessionId} MUX_DISPLAY_NAME=${opts.name} ` +
    `codex app-server -c approval_policy="never" -c sandbox_mode="danger-full-access"${modelFlag}${effortFlag}${pluginFlags}'`
}

// Single source of truth for the cursor invocation broker spawns into a tmux
// pane (or supervisor). Follows the same env-var + bash -lc pattern as claude.
export function buildCursorSpawnCommand(opts: { name: string; sessionId?: string; model?: string; effort?: string; cursorHome?: string; workdir?: string; pluginsFile?: string; pluginsDir?: string }): string {
  const sessionId = opts.sessionId ?? opts.name
  const cursorHome = opts.cursorHome ?? join(STATE_DIR, "agents", "cursor", opts.name)
  const modelFlag = opts.model ? ` --model ${opts.model}` : ""
  const { args: pluginArgs } = cursorSpawnArgs({ sessionName: opts.name, file: opts.pluginsFile, pluginsDir: opts.pluginsDir, onError: (msg) => log.warn("plugins_registry_invalid", { err: msg }) })
  const pluginFlags = pluginArgs.length ? ` ${pluginArgs.join(" ")}` : ""
  return `bash -lc 'HOME=${cursorHome} MUX_SESSION_ID=${sessionId} MUX_DISPLAY_NAME=${opts.name} cursor-agent${modelFlag}${pluginFlags}'`
}

// Single source of truth for the opencode invocation broker spawns into a tmux
// pane (or supervisor). Follows the same env-var + bash -lc pattern as claude.
export function buildOpenCodeSpawnCommand(opts: { name: string; sessionId?: string; model?: string; effort?: string; configHome?: string; workdir?: string; port?: number; pluginsFile?: string; pluginsDir?: string }): string {
  const sessionId = opts.sessionId ?? opts.name
  const configHome = opts.configHome ?? join(STATE_DIR, "agents", "opencode", opts.name)
  const port = opts.port ?? 0
  const modelFlag = opts.model ? ` --model ${opts.model}` : ""
  return `bash -lc 'XDG_CONFIG_HOME=${configHome} MUX_SESSION_ID=${sessionId} MUX_DISPLAY_NAME=${opts.name} ` +
    `opencode serve --hostname 127.0.0.1 --port ${port}${modelFlag}'`
}

// Resolve supermux plugin-host flags for this session from ~/.mux/plugins.json.
// Each enabled, claude-scoped, compatible plugin contributes a `--plugin-dir`.
// Never throws — a bad registry yields no flags so spawns are never broken.
// Plugin dirs with whitespace/quotes are skipped (they'd corrupt the single-
// quoted bash command); that's logged rather than silently dropped. Also reports
// whether mux-core (which carries the reply-conventions hook) is present,
// so the caller knows whether the static reply fallback is needed.
function buildPluginFlags(sessionName: string, file?: string, pluginsDir?: string): { flags: string; hasCorePlugin: boolean } {
  const { args } = claudeSpawnArgs({ sessionName, file, pluginsDir, onError: (msg) => log.warn("plugins_registry_invalid", { err: msg }) })
  let out = ""
  let hasCorePlugin = false
  for (let i = 0; i < args.length; i += 2) {
    const flag = args[i]          // "--plugin-dir"
    const dir = args[i + 1] ?? ""
    if (/[\s'"]/.test(dir)) {
      log.warn("plugin_dir_unsafe_skipped", { dir })
      continue
    }
    if (basename(dir) === CORE_PLUGIN_NAME) hasCorePlugin = true
    out += ` ${flag} ${dir}`
  }
  return { flags: out, hasCorePlugin }
}

function writeSessionMemoryPreamble(sessionName: string, role: AgentRole, workdir?: string): string {
  const dir = resolve(STATE_DIR, "memory-preambles")
  mkdirSync(dir, { recursive: true })
  const preamble = buildMemoryPreamble(role, sessionName, workdir)
  const path = resolve(dir, `${sessionName}.md`)
  writeFileSync(path, preamble, "utf8")
  return path
}
