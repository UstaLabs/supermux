/** Mirrors broker `ActivityToolBody` (src/core/agents/activity-body.ts). */

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
      mode?: string
      diff?: string
      oldText?: string
      newText?: string
      files?: Array<{
        path: string
        rawPath?: string
        mode?: string
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

export function isBashBody(b: ActivityToolBody | undefined): b is Extract<ActivityToolBody, { kind: "bash" }> {
  return b?.kind === "bash"
}

export function isEditBody(b: ActivityToolBody | undefined): b is Extract<ActivityToolBody, { kind: "edit" }> {
  return b?.kind === "edit"
}

export function isWriteBody(b: ActivityToolBody | undefined): b is Extract<ActivityToolBody, { kind: "write" }> {
  return b?.kind === "write"
}

/** Prefer structured body; fall back to medium input/output strings. */
export function resolveBashParts(opts: {
  body?: ActivityToolBody
  resultBody?: ActivityToolBody
  input?: string
  output?: string
  toolName: string
}): { command?: string; output?: string; exitCode?: number | null } {
  const start = isBashBody(opts.body) ? opts.body : undefined
  const end = isBashBody(opts.resultBody) ? opts.resultBody : undefined
  const command = start?.command || (opts.toolName === "Bash" ? opts.input : undefined) || undefined
  const output = end?.output || start?.output || opts.output || undefined
  const exitCode = end?.exitCode ?? start?.exitCode
  return { command, output, exitCode }
}

export function resolveEditParts(opts: {
  body?: ActivityToolBody
  resultBody?: ActivityToolBody
  input?: string
  toolName: string
}): {
  path: string
  mode?: string
  diff?: string
  content?: string
  files?: Extract<ActivityToolBody, { kind: "edit" }>["files"]
} | null {
  if (isEditBody(opts.body)) {
    return {
      path: opts.body.path,
      mode: opts.body.mode,
      diff: opts.body.diff,
      files: opts.body.files,
    }
  }
  if (isWriteBody(opts.body)) {
    return {
      path: opts.body.path,
      mode: "add",
      content: opts.body.content,
      diff: opts.body.content
        ? opts.body.content.split("\n").map((l) => `+${l}`).join("\n")
        : undefined,
    }
  }
  // Heuristic fallback for Edit/Write without body (older sessions).
  if (opts.toolName === "Edit" || opts.toolName === "Write") {
    const path = (opts.input || "").split("\n")[0]?.replace(/^(update|add|delete|move)\s+/i, "").trim() || "file"
    // If input looks like a unified diff or multi-line edit payload, use it.
    const looksDiff = !!(opts.input && (opts.input.includes("\n+") || opts.input.includes("\n-") || opts.input.includes("@@")))
    return {
      path: path.length > 120 ? path.slice(0, 120) : path,
      diff: looksDiff ? opts.input : undefined,
      content: !looksDiff && opts.toolName === "Write" ? opts.input : undefined,
    }
  }
  return null
}
