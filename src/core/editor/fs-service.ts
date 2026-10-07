export interface DiffEntry {
  path: string
  status: string
  diff: string
  binary?: boolean
  modeChange?: boolean
}

/**
 * Parses unified diff output into structured entries.
 */
export function parseDiff(raw: string): DiffEntry[] {
  const entries: DiffEntry[] = []

  // Split on "diff --git" headers
  const chunks = raw.split(/^(?=diff --git )/m).filter(Boolean)

  for (const chunk of chunks) {
    // Extract file path from "diff --git a/... b/..." (paths may be quoted by git)
    // Unquoted form:  diff --git a/foo b/foo
    // Quoted form:    diff --git "a/foo\"bar" "b/foo\"bar"
    const headerMatch =
      chunk.match(/^diff --git "a\/((?:[^"\\]|\\.)*)" "b\/((?:[^"\\]|\\.)*)"/m) ??
      chunk.match(/^diff --git a\/(.*?) b\/(.*)$/m)
    if (!headerMatch) continue

    // Unescape C-style backslash sequences git uses inside quoted paths
    const unescapeGitPath = (s: string) => s.replace(/\\(.)/g, "$1")
    const path = unescapeGitPath(headerMatch[2]!.trim())

    // Determine status
    let status = "modified"
    if (/^new file mode/m.test(chunk)) status = "added"
    else if (/^deleted file mode/m.test(chunk)) status = "deleted"
    else if (/^rename/m.test(chunk)) status = "renamed"

    const binary = /^Binary files /m.test(chunk)
    const modeChange = /^old mode /m.test(chunk) && !/^@@/m.test(chunk)

    entries.push({ path, status, diff: chunk, binary, modeChange })
  }

  return entries
}
