// Pure parsers for the login CLIs' stdout. Tolerant by design — formats are
// only semi-documented (codex) or undocumented (cursor), so we extract by
// pattern and the caller degrades gracefully when these return null.

const URL_RE = /https?:\/\/[^\s'"\x00-\x1f\x7f]+/
const CODE_RE = /\b([A-Z0-9]{3,}-[A-Z0-9]{3,})\b/
// The CLIs colorize their output. Strip ANSI SGR escapes (e.g. \x1b[94m … \x1b[0m)
// BEFORE pattern-matching, otherwise the URL match swallows a trailing reset
// escape and the device code loses the word boundary against the "…m" prefix —
// which made codex device-auth silently fail to surface (no url/code parsed).
const ANSI_RE = /\x1b\[[0-9;]*m/g
// Claude renders its OAuth URL as an OSC 8 terminal hyperlink. The hyperlink
// target appears inside an escape sequence before the visible URL, so matching
// before removing OSC sequences returns a corrupt URL containing BEL/ESC bytes.
const OSC_RE = /\x1b\][^\x07]*(?:\x07|\x1b\\)/g
function stripAnsi(s: string): string {
  return s.replace(OSC_RE, "").replace(ANSI_RE, "")
}

export interface CodexDeviceAuth {
  url: string
  code: string
}

/** Returns {url, code} once BOTH appear in the accumulated stdout, else null. */
export function parseCodexDeviceAuth(stdout: string): CodexDeviceAuth | null {
  const clean = stripAnsi(stdout)
  const url = clean.match(URL_RE)?.[0]
  const code = clean.match(CODE_RE)?.[1]
  if (url && code) return { url, code }
  return null
}

/** Returns the first http(s) URL in the accumulated stdout, else null. */
export function parseCursorLoginUrl(stdout: string): string | null {
  return stripAnsi(stdout).match(URL_RE)?.[0] ?? null
}
