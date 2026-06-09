// Editor settings persisted in the `settings` table (broker-wide). The PWA
// settings page edits these; LSP bridge respects per-server enable flags.

import { SERVERS, type LspInstallSpec, type LspServerSpec } from "../lsp/catalog"

function isCatalogServerId(id: string): boolean {
  return SERVERS.some((s) => s.id === id)
}

export const SETTINGS_KEY_EDITOR = "editor"

const SERVER_ID_RE = /^[a-z][a-z0-9_-]{0,47}$/
const EXT_RE = /^\.[a-zA-Z0-9][a-zA-Z0-9_-]*$/

export interface EditorLspServerConfig {
  /** When false, files matching this server are treated as unsupported for LSP. */
  enabled?: boolean
}

/** User-defined language server (broker spawns `command` + `args`). */
export interface CustomLspServerDef {
  label: string
  /** Executable name on PATH or absolute path on the broker host. */
  command: string
  args?: string[]
  /** File extensions with leading dot, e.g. [".zig", ".zon"]. */
  extensions: string[]
  /** LSP languageId sent in didOpen (defaults to extension without dot). */
  languageId?: string
  /** Optional one-click install (broker user — no sudo). e.g. "apt install -y zls". */
  installCmd?: string
}

export interface EditorConfig {
  lsp?: {
    servers?: Record<string, EditorLspServerConfig>
    custom?: Record<string, CustomLspServerDef>
  }
}

export function defaultEditorConfig(): EditorConfig {
  return { lsp: { servers: {}, custom: {} } }
}

export function listCustomServerDefs(cfg: EditorConfig = defaultEditorConfig()): { id: string; def: CustomLspServerDef }[] {
  const custom = cfg.lsp?.custom ?? {}
  return Object.entries(custom).map(([id, def]) => ({ id, def }))
}

/** Broker runs as the service user — strip a leading `sudo` users paste from docs. */
export function stripSudoPrefix(cmd: string): string {
  return cmd.trim().replace(/^sudo\s+/, "")
}

export function customToServerSpec(id: string, def: CustomLspServerDef): LspServerSpec {
  const parsed = parseCustomLspServerDef(def)
  let install: LspInstallSpec | undefined
  if (parsed.installCmd) {
    const cmd = parsed.installCmd.split(/\s+/).filter(Boolean)
    install = {
      cmd,
      label: parsed.installCmd,
      requires: cmd[0] ?? "sh",
    }
  }
  return {
    id,
    label: parsed.label,
    runtime: "native",
    bin: parsed.command.split("/").pop() ?? parsed.command,
    command: parsed.command,
    args: parsed.args ?? [],
    extensions: parsed.extensions,
    languageId: parsed.languageId,
    custom: true,
    install,
  }
}

export function parseCustomLspServerId(id: unknown): string | null {
  if (typeof id !== "string") return null
  const s = id.trim().toLowerCase()
  if (!SERVER_ID_RE.test(s)) return null
  if (isCatalogServerId(s)) return null
  return s
}

export function parseCustomLspServerDef(input: unknown): CustomLspServerDef {
  const o = (input ?? {}) as Record<string, unknown>
  const label = typeof o.label === "string" ? o.label.trim().slice(0, 80) : ""
  if (!label) throw new Error("label is required")

  let command = typeof o.command === "string" ? o.command.trim() : ""
  command = stripSudoPrefix(command)
  if (!command || command.length > 512) throw new Error("command is required")
  if (/^sudo$/i.test(command) || /^sudo\s/i.test(command)) throw new Error("command must not use sudo")
  if (/[\0\n\r|&;$`<>]/.test(command)) throw new Error("invalid command")

  const args = parseArgs(o.args)
  const extensions = parseExtensions(o.extensions)
  if (extensions.length === 0) throw new Error("at least one extension is required")

  let languageId: string | undefined
  if (o.languageId !== undefined && o.languageId !== null && o.languageId !== "") {
    languageId = String(o.languageId).trim().slice(0, 64)
    if (!/^[a-zA-Z0-9_-]+$/.test(languageId)) throw new Error("invalid languageId")
  }

  let installCmd: string | undefined
  if (o.installCmd !== undefined && o.installCmd !== null && o.installCmd !== "") {
    installCmd = stripSudoPrefix(String(o.installCmd).trim().slice(0, 512))
    if (!installCmd) installCmd = undefined
    else if (/^sudo$/i.test(installCmd.split(/\s+/)[0] ?? "")) throw new Error("install command must not use sudo")
    else if (/[\0\n\r|&;$`<>]/.test(installCmd)) throw new Error("invalid install command")
  }

  return { label, command, args, extensions, languageId, installCmd }
}

function parseArgs(input: unknown): string[] {
  if (input === undefined || input === null) return []
  if (typeof input === "string") {
    const t = input.trim()
    return t ? t.split(/\s+/).map((a) => a.slice(0, 256)) : []
  }
  if (!Array.isArray(input)) throw new Error("args must be a string or array")
  return input.map((a) => String(a).slice(0, 256))
}

function parseExtensions(input: unknown): string[] {
  let raw: string[] = []
  if (typeof input === "string") {
    raw = input.split(/[\s,]+/).map((s) => s.trim()).filter(Boolean)
  } else if (Array.isArray(input)) {
    raw = input.map((e) => String(e).trim()).filter(Boolean)
  } else {
    throw new Error("extensions is required")
  }
  const out: string[] = []
  for (let ext of raw) {
    if (!ext.startsWith(".")) ext = `.${ext}`
    ext = ext.toLowerCase()
    if (!EXT_RE.test(ext)) throw new Error(`invalid extension: ${ext}`)
    if (!out.includes(ext)) out.push(ext)
  }
  return out
}

/** Unknown server ids are ignored for built-in toggles; custom ids allowed. */
export function isLspServerEnabled(serverId: string, cfg: EditorConfig = defaultEditorConfig()): boolean {
  const entry = cfg.lsp?.servers?.[serverId]
  if (entry?.enabled === false) return false
  return true
}

export function sanitizeLspServerPatch(input: unknown): Record<string, EditorLspServerConfig> {
  if (!input || typeof input !== "object") return {}
  const out: Record<string, EditorLspServerConfig> = {}
  for (const [id, raw] of Object.entries(input as Record<string, unknown>)) {
    if (!id || typeof id !== "string") continue
    const o = (raw ?? {}) as Record<string, unknown>
    out[id] = { enabled: o.enabled === undefined ? undefined : Boolean(o.enabled) }
  }
  return out
}

function sanitizeCustomPatch(input: unknown): Record<string, CustomLspServerDef> {
  if (!input || typeof input !== "object") return {}
  const out: Record<string, CustomLspServerDef> = {}
  for (const [rawId, rawDef] of Object.entries(input as Record<string, unknown>)) {
    const id = parseCustomLspServerId(rawId)
    if (!id) continue
    try {
      out[id] = parseCustomLspServerDef(rawDef)
    } catch {
      // skip invalid entries on merge
    }
  }
  return out
}

/** Re-sanitize stored custom servers (strips leading sudo from command/installCmd). */
function normalizeStoredCustom(custom: Record<string, unknown> | undefined): Record<string, CustomLspServerDef> {
  if (!custom || typeof custom !== "object") return {}
  const out: Record<string, CustomLspServerDef> = {}
  for (const [rawId, rawDef] of Object.entries(custom)) {
    const id = parseCustomLspServerId(rawId)
    if (!id) continue
    try {
      out[id] = parseCustomLspServerDef(rawDef)
    } catch {
      // drop invalid legacy rows
    }
  }
  return out
}

export function parseEditorConfig(input: unknown, base: EditorConfig = defaultEditorConfig()): EditorConfig {
  const o = (input ?? {}) as Record<string, unknown>
  const lspIn = (o.lsp ?? {}) as Record<string, unknown>
  const baseServers = base.lsp?.servers ?? {}
  const patch = sanitizeLspServerPatch(lspIn.servers)
  const customPatch = sanitizeCustomPatch(lspIn.custom)
  const mergedServers: Record<string, EditorLspServerConfig> = { ...baseServers }
  for (const [id, entry] of Object.entries(patch)) {
    mergedServers[id] = { ...mergedServers[id], ...entry }
  }
  const mergedCustom = {
    ...normalizeStoredCustom(lspIn.custom as Record<string, unknown> | undefined),
    ...customPatch,
  }
  return { lsp: { servers: mergedServers, custom: mergedCustom } }
}

/** Merge a sparse patch (e.g. from PUT body) into stored config. */
export function mergeEditorConfigPatch(stored: unknown, patch: unknown): EditorConfig {
  const base = parseEditorConfig(stored)
  const p = (patch ?? {}) as EditorConfig
  const serverPatch = sanitizeLspServerPatch(p.lsp?.servers)
  const customPatch = sanitizeCustomPatch(p.lsp?.custom)
  const mergedServers = { ...base.lsp?.servers }
  for (const [id, entry] of Object.entries(serverPatch)) {
    mergedServers[id] = { ...mergedServers[id], ...entry }
  }
  const mergedCustom = { ...base.lsp?.custom, ...customPatch }
  return { lsp: { servers: mergedServers, custom: mergedCustom } }
}
