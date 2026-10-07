// src/core/forge/host.ts
// Scheme handling for self-hosted forges. A user who types "http://git.acme.com"
// means it — plaintext instances exist on trusted networks — so the scheme they
// enter is preserved end-to-end (API calls, clone URL, credential binding)
// instead of being silently upgraded to https.
export type Scheme = "http" | "https"

/** Split a user-entered host/base into scheme + bare host. Scheme-less input is https. */
export function parseHostInput(raw: string): { host: string; scheme: Scheme } {
  const trimmed = raw.trim()
  const m = trimmed.match(/^(https?):\/\/(.*)$/i)
  const scheme: Scheme = m?.[1]?.toLowerCase() === "http" ? "http" : "https"
  const rest = (m?.[2] ?? trimmed).replace(/\/+$/, "")
  return { host: rest, scheme }
}

/** The scheme a stored apiBase uses. Anything not explicitly http is treated as https. */
export function schemeOf(apiBase: string): Scheme {
  return /^http:\/\//i.test(apiBase.trim()) ? "http" : "https"
}

/** Force `url` to `scheme` (adapters always derive https; this applies the user's choice). */
export function withScheme(url: string, scheme: Scheme): string {
  return url.replace(/^https?:\/\//i, `${scheme}://`)
}
