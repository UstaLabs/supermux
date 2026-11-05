// Pure parsers for the login CLIs' stdout. Tolerant by design — formats are
// only semi-documented (codex) or undocumented (cursor), so we extract by
// pattern and the caller degrades gracefully when these return null.

// The CLIs colorize their output, Claude prints its URL as an OSC 8 hyperlink
// (ended by ESC \ on Windows), and a ConPTY moves the cursor instead of printing
// line breaks: core's stripAnsi (the account login's) handles all of it, so the
// URL match neither swallows an escape nor runs into the next line.
import { stripAnsi } from "../../../../packages/supermux-core/src/accounts/index.js"

const URL_RE = /https?:\/\/[^\s'"\x00-\x1f\x7f]+/
const CODE_RE = /\b([A-Z0-9]{3,}-[A-Z0-9]{3,})\b/

export interface CodexDeviceAuth {
  url: string
  code: string
}

/** Returns {url, code} once BOTH appear in the accumulated stdout, else null.
 *
 * Shared by every CLI whose device-code output is "a URL plus an XXXX-XXXX code",
 * which so far is codex and grok. grok prints the code twice (once embedded in the
 * URL's `?user_code=`, once standalone); both spellings yield the same value, so
 * the first match is correct whichever arrives first. */
export function parseDeviceAuth(stdout: string): CodexDeviceAuth | null {
  const clean = stripAnsi(stdout)
  const url = clean.match(URL_RE)?.[0]
  const code = clean.match(CODE_RE)?.[1]
  if (url && code) return { url, code }
  return null
}

export function parseCodexDeviceAuth(stdout: string): CodexDeviceAuth | null {
  return parseDeviceAuth(stdout)
}

/** grok: `grok login --device-auth` prints the codex-shaped URL + code. */
export function parseGrokDeviceAuth(stdout: string): CodexDeviceAuth | null {
  return parseDeviceAuth(stdout)
}

/** antigravity: agy's print-mode login prints a Google OAuth URL (accounts.google.com), then
 * "Or, paste the authorization code here". Only that host counts — any other URL agy may print
 * (docs, status) is not the login link. */
export function parseAntigravityLoginUrl(stdout: string): string | null {
  const clean = stripAnsi(stdout)
  for (const match of clean.matchAll(new RegExp(URL_RE.source, "g"))) {
    try {
      if (new URL(match[0]).hostname === "accounts.google.com") return match[0]
    } catch { /* not a URL */ }
  }
  return null
}

/** Returns the first http(s) URL in the accumulated stdout, else null. */
export function parseCursorLoginUrl(stdout: string): string | null {
  return stripAnsi(stdout).match(URL_RE)?.[0] ?? null
}
