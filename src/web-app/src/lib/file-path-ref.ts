export type FilePathRef = {
  path: string
  line?: number
  endLine?: number
}

/** Path body shared with markdown linkification (relative, absolute, home-relative). */
export const FILE_PATH_BODY =
  "(?:\\.{0,2}\\/)?(?:[\\w@.-]+\\/)+[\\w.-]+\\.[\\w]+|(?:\\/|~\\/)(?:[\\w@.-]+\\/)+[\\w.-]+\\.[\\w]+"

const FILE_PATH_REF_RE = new RegExp(`^(${FILE_PATH_BODY})(?::(.*))?$`)

/** Match file paths with optional line suffix for linkification. */
export const FILE_PATH_MATCH_RE = new RegExp(
  `(?<!\\w)(${FILE_PATH_BODY})(?::\\d+(?:-\\d+)?|:[^\\s<>"'\\w]+)?(?!\\w)`,
  "g",
)

export function parseFilePathRef(raw: string): FilePathRef | null {
  const trimmed = raw.trim()
  const m = trimmed.match(FILE_PATH_REF_RE)
  if (!m) return null

  const path = m[1]
  const suffix = m[2]
  if (suffix === undefined) return { path }

  const lineMatch = suffix.match(/^(\d+)(?:-(\d+))?$/)
  if (!lineMatch) return null

  const line = Number(lineMatch[1])
  const endLine = lineMatch[2] !== undefined ? Number(lineMatch[2]) : undefined
  if (endLine !== undefined && line > endLine) return null

  if (endLine !== undefined) return { path, line, endLine }
  return { path, line }
}

export function stripFilePathRefSuffix(raw: string): string {
  const ref = parseFilePathRef(raw.trim())
  if (ref) return ref.path
  return raw.trim().replace(/:(\d+)(?:-(\d+))?$/, "")
}

export function formatFilePathRef(ref: FilePathRef): string {
  if (ref.line === undefined) return ref.path
  if (ref.endLine !== undefined) return `${ref.path}:${ref.line}-${ref.endLine}`
  return `${ref.path}:${ref.line}`
}
