import { join } from "path"
import { STATE_DIR } from "../../shared/paths"
import { codexSpawnArgs, cursorSpawnArgs } from "../plugins"
import { makeLogger } from "../../shared/log"

const log = makeLogger("spawn-command")

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

export function buildCursorSpawnCommand(opts: { name: string; sessionId?: string; model?: string; effort?: string; cursorHome?: string; workdir?: string; pluginsFile?: string; pluginsDir?: string }): string {
  const sessionId = opts.sessionId ?? opts.name
  const cursorHome = opts.cursorHome ?? join(STATE_DIR, "agents", "cursor", opts.name)
  const modelFlag = opts.model ? ` --model ${opts.model}` : ""
  const { args: pluginArgs } = cursorSpawnArgs({ sessionName: opts.name, file: opts.pluginsFile, pluginsDir: opts.pluginsDir, onError: (msg) => log.warn("plugins_registry_invalid", { err: msg }) })
  const pluginFlags = pluginArgs.length ? ` ${pluginArgs.join(" ")}` : ""
  return `bash -lc 'HOME=${cursorHome} MUX_SESSION_ID=${sessionId} MUX_DISPLAY_NAME=${opts.name} cursor-agent${modelFlag}${pluginFlags}'`
}

export function buildOpenCodeSpawnCommand(opts: { name: string; sessionId?: string; model?: string; effort?: string; configHome?: string; workdir?: string; port?: number; pluginsFile?: string; pluginsDir?: string }): string {
  const sessionId = opts.sessionId ?? opts.name
  const configHome = opts.configHome ?? join(STATE_DIR, "agents", "opencode", opts.name)
  const port = opts.port ?? 0
  const modelFlag = opts.model ? ` --model ${opts.model}` : ""
  return `bash -lc 'XDG_CONFIG_HOME=${configHome} MUX_SESSION_ID=${sessionId} MUX_DISPLAY_NAME=${opts.name} ` +
    `opencode serve --hostname 127.0.0.1 --port ${port}${modelFlag}'`
}
