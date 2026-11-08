import type { BrowserViewState } from "./types"

const MAX_URL = 4096
const DOC_ID = /^[A-Za-z0-9_-]{1,64}$/

/**
 * Why a Browser tab's state is refused, or undefined when it is fine (#105). The state is the page the tab
 * shows: `{url}`, http or https only, `{doc}`, an agent page's id (#104), or `{}`, a new tab. A client loads the
 * stored URL as it is, so nothing that would run on its own (javascript:, data:, file:) is ever stored.
 */
export function browserStateError(state: unknown): string | undefined {
  if (!state || typeof state !== "object" || Array.isArray(state)) return "browser state must be an object"
  const { url, doc, ...rest } = state as Record<string, unknown>
  if (Object.keys(rest).length) return `unknown browser state keys: ${Object.keys(rest).join(", ")}`
  if (url !== undefined && doc !== undefined) return "browser state can't have both url and doc"
  if (doc !== undefined && (typeof doc !== "string" || !DOC_ID.test(doc))) return "doc must be a short id"
  if (url === undefined) return undefined
  if (typeof url !== "string" || url.length > MAX_URL) return `url must be a string of at most ${MAX_URL} characters`
  let parsed: URL
  try { parsed = new URL(url) } catch { return "url must be an absolute http or https URL" }
  if (parsed.protocol !== "http:" && parsed.protocol !== "https:") return "url must be an absolute http or https URL"
  return undefined
}

export function isBrowserState(state: unknown): state is BrowserViewState {
  return browserStateError(state) === undefined
}
