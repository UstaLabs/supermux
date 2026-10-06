// HTTPS-only, time-boxed downloads for the broker's own (builtin) agent installers.
//   • every request and every redirect hop must stay on https:// (a redirect to http:// is refused
//     once the response says where it landed)
//   • metadata/version lookups get 60 s, binary downloads 10 min, body included
export const METADATA_TIMEOUT_MS = 60_000
export const BINARY_TIMEOUT_MS = 10 * 60_000

/** Where OpenCode's release assets must come from (the API's browser_download_url is pinned to it). */
export const OPENCODE_RELEASE_API = "https://api.github.com/repos/anomalyco/opencode/releases/latest"
export const OPENCODE_DOWNLOAD_PREFIX = "https://github.com/anomalyco/opencode/releases/download/"

const USER_AGENT = "supermux-agent-installer"

export interface HttpsGetOptions {
  timeoutMs: number
  accept?: string
}

/** GET [url] over HTTPS only; throws on a non-2xx answer, a timeout, or a non-HTTPS final URL. */
export async function httpsGet(fetchFn: typeof fetch, url: string, opts: HttpsGetOptions): Promise<Response> {
  if (!url.startsWith("https://")) throw new Error(`refusing ${url}: not an https:// URL`)
  let res: Response
  try {
    res = await fetchFn(url, {
      redirect: "follow",
      headers: { "User-Agent": USER_AGENT, ...(opts.accept ? { Accept: opts.accept } : {}) },
      signal: AbortSignal.timeout(opts.timeoutMs),
    })
  } catch (err) {
    const name = (err as { name?: string })?.name
    if (name === "TimeoutError" || name === "AbortError") throw new Error(`timed out after ${opts.timeoutMs / 1000} s: ${url}`)
    throw err
  }
  if (!res.ok) throw new Error(`download failed: HTTP ${res.status} for ${url}`)
  if (!res.url?.startsWith("https://")) throw new Error(`refusing ${url}: it redirected to a non-HTTPS URL (${res.url || "unknown"})`)
  return res
}

/** The body of an HTTPS download as bytes (10 min budget). */
export async function httpsBytes(fetchFn: typeof fetch, url: string): Promise<Uint8Array> {
  const res = await httpsGet(fetchFn, url, { timeoutMs: BINARY_TIMEOUT_MS })
  return new Uint8Array(await res.arrayBuffer())
}

/** A release asset URL must be OpenCode's own GitHub release download. */
export function assertOpenCodeAssetUrl(url: string): void {
  if (!url.startsWith(OPENCODE_DOWNLOAD_PREFIX)) throw new Error(`refusing the OpenCode asset ${url}: not under ${OPENCODE_DOWNLOAD_PREFIX}`)
}
