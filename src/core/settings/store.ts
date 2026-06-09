import type { Database } from "bun:sqlite"
import {
  type CuratorConfig,
  SETTINGS_KEY_CURATOR,
  defaultCuratorConfig,
  parseCuratorConfig,
} from "./curator-config"
import {
  type AppConfig,
  type AppConfigEnv,
  SETTINGS_KEY_APP,
  sanitizeAppConfigPatch,
  resolveAppConfig,
} from "./app-config"
import {
  type CustomLspServerDef,
  type EditorConfig,
  SETTINGS_KEY_EDITOR,
  defaultEditorConfig,
  mergeEditorConfigPatch,
  parseCustomLspServerDef,
  parseCustomLspServerId,
  parseEditorConfig,
  sanitizeLspServerPatch,
} from "./editor-config"
import { getServerById } from "../lsp/registry"

type SettingsRow = { key: string; value: string }

/**
 * Write-through key→JSON settings store, backed by the `settings` table. Mirrors
 * the ProxyStore/SessionStore pattern: an in-memory cache is the working set,
 * every mutation also hits the DB, so they can't drift. Source of truth for
 * UI-editable broker config.
 */
export class SettingsStore {
  private cache = new Map<string, unknown>()

  constructor(private readonly db: Database) {
    for (const row of this.db.query("SELECT key, value FROM settings").all() as SettingsRow[]) {
      try {
        this.cache.set(row.key, JSON.parse(row.value))
      } catch {
        // ignore a corrupt row; the default-returning getters cover it
      }
    }
  }

  get<T>(key: string): T | undefined {
    return this.cache.get(key) as T | undefined
  }

  set(key: string, value: unknown): void {
    this.cache.set(key, value)
    this.db.run("INSERT INTO settings (key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value", [
      key,
      JSON.stringify(value),
    ])
  }

  has(key: string): boolean {
    return this.cache.has(key)
  }

  /** Current curator config, falling back to defaults when unset/partial. */
  getCurator(): CuratorConfig {
    return parseCuratorConfig(this.cache.get(SETTINGS_KEY_CURATOR), defaultCuratorConfig)
  }

  setCurator(cfg: CuratorConfig): void {
    this.set(SETTINGS_KEY_CURATOR, parseCuratorConfig(cfg))
  }

  /** Resolved app config: stored sparse partial → env → built-in default. */
  getAppConfig(env: AppConfigEnv): AppConfig {
    return resolveAppConfig(sanitizeAppConfigPatch(this.cache.get(SETTINGS_KEY_APP)), env)
  }

  /** Persist a sparse merge — unset fields are NOT defaulted, so env seeds keep showing through. */
  setAppConfig(patch: Partial<AppConfig>): void {
    const current = sanitizeAppConfigPatch(this.cache.get(SETTINGS_KEY_APP))
    this.set(SETTINGS_KEY_APP, { ...current, ...sanitizeAppConfigPatch(patch) })
  }

  getEditorConfig(): EditorConfig {
    return parseEditorConfig(this.cache.get(SETTINGS_KEY_EDITOR), defaultEditorConfig())
  }

  setEditorConfig(patch: Partial<EditorConfig>): EditorConfig {
    const current = this.getEditorConfig()
    let toMerge = patch
    if (patch.lsp?.servers) {
      const servers: Record<string, import("./editor-config").EditorLspServerConfig> = {}
      for (const [id, cfg] of Object.entries(sanitizeLspServerPatch(patch.lsp.servers))) {
        if (getServerById(id, current)) servers[id] = cfg
      }
      toMerge = { ...patch, lsp: { ...patch.lsp, servers } }
    }
    const next = mergeEditorConfigPatch(this.cache.get(SETTINGS_KEY_EDITOR), toMerge)
    this.set(SETTINGS_KEY_EDITOR, next)
    return this.getEditorConfig()
  }

  addCustomLspServer(id: string, def: CustomLspServerDef): EditorConfig {
    const parsedId = parseCustomLspServerId(id)
    if (!parsedId) throw new Error("invalid server id (use lowercase letters, numbers, hyphens; cannot match a built-in id)")
    const parsed = parseCustomLspServerDef(def)
    const cfg = this.getEditorConfig()
    const custom = { ...cfg.lsp?.custom, [parsedId]: parsed }
    const servers = { ...cfg.lsp?.servers, [parsedId]: { enabled: true } }
    const next: EditorConfig = { lsp: { servers, custom } }
    this.set(SETTINGS_KEY_EDITOR, next)
    return next
  }

  removeCustomLspServer(id: string): EditorConfig {
    const parsedId = parseCustomLspServerId(id)
    if (!parsedId) throw new Error("invalid server id")
    const cfg = this.getEditorConfig()
    if (!cfg.lsp?.custom?.[parsedId]) throw new Error("custom server not found")
    const custom = { ...cfg.lsp.custom }
    delete custom[parsedId]
    const servers = { ...cfg.lsp?.servers }
    delete servers[parsedId]
    const next: EditorConfig = { lsp: { servers, custom } }
    this.set(SETTINGS_KEY_EDITOR, next)
    return next
  }
}
