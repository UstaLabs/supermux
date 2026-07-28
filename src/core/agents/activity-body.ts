// Structured tool payloads for high-detail chat rendering.
// Medium keeps using title/detail strings; High prefers `body`.

import { clip, firstLine } from "./activity-format"

/** Soft cap for structured body string fields (High may scroll within this). */
export const BODY_MAX = 100_000

/** Soft cap for human "why" tool descriptions (Claude description, OpenCode title, …). */
export const DESCRIPTION_MAX = 300

export type ActivityEditMode = "update" | "add" | "delete" | "move" | string

export type ActivityToolBody =
  | {
      kind: "bash"
      command?: string
      output?: string
      exitCode?: number | null
    }
  | {
      kind: "edit"
      path: string
      rawPath?: string
      mode?: ActivityEditMode
      /** Unified diff when available (preferred for rendering). */
      diff?: string
      oldText?: string
      newText?: string
      /** Multi-file edits (codex fileChange). */
      files?: Array<{
        path: string
        rawPath?: string
        mode?: ActivityEditMode
        diff?: string
      }>
    }
  | {
      kind: "write"
      path: string
      rawPath?: string
      content?: string
    }
  | {
      kind: "generic"
      input?: string
      output?: string
    }

export function clipBodyField(s: string | undefined, max = BODY_MAX): { text: string; truncated: boolean } {
  if (!s) return { text: "", truncated: false }
  return clip(s, max)
}

/** Clip every string field on a body; returns body + whether anything was truncated.
 * Omits empty/undefined optional fields so wire payloads stay sparse. */
export function clipToolBody(body: ActivityToolBody | undefined): { body?: ActivityToolBody; truncated: boolean } {
  if (!body) return { body: undefined, truncated: false }
  let truncated = false
  const take = (s: string | undefined): string | undefined => {
    if (s == null || s === "") return undefined
    const c = clipBodyField(s)
    if (c.truncated) truncated = true
    return c.text || undefined
  }

  if (body.kind === "bash") {
    const command = take(body.command)
    const output = take(body.output)
    const exitCode = body.exitCode
    if (!command && !output && exitCode === undefined) return { body: undefined, truncated: false }
    return {
      body: {
        kind: "bash",
        ...(command ? { command } : {}),
        ...(output ? { output } : {}),
        ...(exitCode !== undefined ? { exitCode } : {}),
      },
      truncated,
    }
  }
  if (body.kind === "write") {
    const content = take(body.content)
    return {
      body: {
        kind: "write",
        path: body.path,
        ...(body.rawPath ? { rawPath: body.rawPath } : {}),
        ...(content ? { content } : {}),
      },
      truncated,
    }
  }
  if (body.kind === "generic") {
    const input = take(body.input)
    const output = take(body.output)
    if (!input && !output) return { body: undefined, truncated: false }
    return {
      body: {
        kind: "generic",
        ...(input ? { input } : {}),
        ...(output ? { output } : {}),
      },
      truncated,
    }
  }
  // edit
  const files = body.files?.map((f) => {
    const fDiff = take(f.diff)
    return {
      path: f.path,
      ...(f.rawPath ? { rawPath: f.rawPath } : {}),
      ...(f.mode ? { mode: f.mode } : {}),
      ...(fDiff ? { diff: fDiff } : {}),
    }
  })
  const diff = take(body.diff)
  const oldText = take(body.oldText)
  const newText = take(body.newText)
  return {
    body: {
      kind: "edit",
      path: body.path,
      ...(body.rawPath ? { rawPath: body.rawPath } : {}),
      ...(body.mode ? { mode: body.mode } : {}),
      ...(diff ? { diff } : {}),
      ...(oldText ? { oldText } : {}),
      ...(newText ? { newText } : {}),
      ...(files?.length ? { files } : {}),
    },
    truncated,
  }
}

/**
 * Build a simple unified-diff style block from old/new text (replace-style edits).
 * Not a full Myers diff — enough for High UI coloring of agent string-replacements.
 */
export function synthesizeUnifiedDiff(path: string, oldText: string, newText: string): string {
  const oldLines = oldText.length ? oldText.split("\n") : []
  const newLines = newText.length ? newText.split("\n") : []
  // Drop a single trailing empty from split when source ends with newline-less content
  // (keep exact lines as provided by the agent).
  const header = [
    `--- a/${path}`,
    `+++ b/${path}`,
    `@@ -1,${Math.max(oldLines.length, 1)} +1,${Math.max(newLines.length, 1)} @@`,
  ]
  const body = [
    ...oldLines.map((l) => `-${l}`),
    ...newLines.map((l) => `+${l}`),
  ]
  return [...header, ...body].join("\n")
}

/** Prefer an existing unified diff; else synthesize from old/new. */
export function ensureEditDiff(args: {
  path: string
  diff?: string
  oldText?: string
  newText?: string
}): string | undefined {
  if (args.diff && args.diff.trim()) return args.diff.trim()
  if (args.oldText != null || args.newText != null) {
    return synthesizeUnifiedDiff(args.path, args.oldText ?? "", args.newText ?? "")
  }
  return undefined
}

export function strField(obj: Record<string, unknown> | undefined, keys: string[]): string {
  if (!obj) return ""
  for (const k of keys) {
    const v = obj[k]
    if (typeof v === "string" && v) return v
  }
  return ""
}

export function numField(obj: Record<string, unknown> | undefined, keys: string[]): number | undefined {
  if (!obj) return undefined
  for (const k of keys) {
    const v = obj[k]
    if (typeof v === "number" && Number.isFinite(v)) return v
  }
  return undefined
}

/**
 * Normalize a human "why am I running this tool" label.
 * Drops empties and values that are just the command/path repeated (not a real description).
 */
export function cleanToolDescription(
  raw: string | undefined | null,
  notEqualTo: Array<string | undefined | null> = [],
): string | undefined {
  if (raw == null) return undefined
  const t = firstLine(String(raw)).trim()
  if (!t) return undefined
  const lower = t.toLowerCase()
  for (const n of notEqualTo) {
    if (n == null || n === "") continue
    const other = firstLine(String(n)).trim()
    if (!other) continue
    if (t === other || lower === other.toLowerCase()) return undefined
  }
  // Bare tool-name stems ("write", "bash", "edit") are not useful "why" labels.
  if (/^(write|read|edit|bash|shell|grep|glob|search|tool|task)$/i.test(t)) return undefined
  const clipped = clip(t, DESCRIPTION_MAX)
  return clipped.text || undefined
}

/** Prefer explicit description fields from a tool-args object. */
export function pickDescriptionField(obj: Record<string, unknown> | undefined): string {
  return strField(obj, [
    "description",
    "desc",
    "explanation",
    "reason",
    "purpose",
    "label",
  ])
}
