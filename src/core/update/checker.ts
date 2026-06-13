// UpdateChecker: polls versions.json for a newer release, ETag-aware, with a
// one-shot GitHub-releases fallback. Pure-ish + injectable (fetch + timers) so
// it's fully unit-testable with no real network. The apply engine later reuses
// this same instance to surface progress via setState() and to read the last
// good manifest via latestManifest().
import {
  compareVersions,
  isUpdateAvailable,
  parseVersionsJson,
  type VersionsJson,
} from "./versions"

export type UpdateMode = "binary" | "source" | "docker"

export type UpdateState =
  | "idle"
  | "checking"
  | "downloading"
  | "swapping"
  | "restart-required"
  | "failed"

export interface UpdateStatus {
  current: string
  commit: string
  latest: string | null
  updateAvailable: boolean
  notesUrl: string | null
  mode: UpdateMode
  state: UpdateState
  lastChecked: number | null
  lastError: string | null
}

// The callable surface of `fetch`. We accept this rather than the full
// `typeof fetch` so a test can inject a plain async function without also
// having to provide Bun's static `fetch.preconnect`. The real global `fetch`
// is assignable to this.
export type FetchLike = (
  input: string | URL | Request,
  init?: RequestInit,
) => Promise<Response>

export interface UpdateCheckerOptions {
  url: string
  currentVersion: string
  commit: string
  mode: UpdateMode
  fetchImpl?: FetchLike
  intervalMs?: number
  bootJitterMs?: number
  fallbackUrl?: string
}

const DEFAULT_INTERVAL_MS = 6 * 60 * 60 * 1000 // 6h
const DEFAULT_FALLBACK_URL = "https://api.github.com/repos/UstaLabs/supermux/releases/latest"
const FETCH_TIMEOUT_MS = 10_000
const MAX_BOOT_JITTER_MS = 5 * 60 * 1000 // 0–5 min

function errToString(err: unknown): string {
  if (err instanceof Error) return err.message
  return String(err)
}

export class UpdateChecker {
  private readonly url: string
  private readonly fallbackUrl: string
  private readonly currentVersion: string
  private readonly commit: string
  private readonly mode: UpdateMode
  private readonly fetchImpl: FetchLike
  private readonly intervalMs: number
  // undefined => compute a random 0–5 min jitter at start() time (not module
  // load / construction), so the jitter is fresh when polling actually begins.
  private readonly bootJitterMs: number | undefined

  // mutable status fields
  private latest: string | null = null
  private notesUrl: string | null = null
  private state: UpdateState = "idle"
  private lastChecked: number | null = null
  private lastError: string | null = null
  private lastEtag: string | null = null
  private manifest: VersionsJson | null = null

  private bootTimer: ReturnType<typeof setTimeout> | null = null
  private intervalTimer: ReturnType<typeof setInterval> | null = null

  constructor(opts: UpdateCheckerOptions) {
    this.url = opts.url
    this.fallbackUrl = opts.fallbackUrl ?? DEFAULT_FALLBACK_URL
    this.currentVersion = opts.currentVersion
    this.commit = opts.commit
    this.mode = opts.mode
    this.fetchImpl = opts.fetchImpl ?? fetch
    this.intervalMs = opts.intervalMs ?? DEFAULT_INTERVAL_MS
    // Keep the raw option (may be 0 for tests/immediate, or undefined → random
    // resolved at start() time). Do NOT call Math.random here.
    this.bootJitterMs = opts.bootJitterMs
  }

  status(): UpdateStatus {
    return {
      current: this.currentVersion,
      commit: this.commit,
      latest: this.latest,
      updateAvailable:
        this.latest !== null && isUpdateAvailable(this.currentVersion, this.latest),
      notesUrl: this.notesUrl,
      mode: this.mode,
      state: this.state,
      lastChecked: this.lastChecked,
      lastError: this.lastError,
    }
  }

  /** Last successfully parsed versions.json (asset URLs + sha256 for apply). */
  latestManifest(): VersionsJson | null {
    return this.manifest
  }

  /**
   * Used by the apply engine to push progress (downloading/swapping/...) and
   * terminal states through this same status object that the API/CLI/PWA read.
   */
  setState(state: UpdateState, error?: string): void {
    this.state = state
    if (error !== undefined) this.lastError = error
  }

  /**
   * Run a single check. Never throws and never leaves state stuck in "checking":
   * any fetch/parse failure is recorded in lastError, falls back once to GitHub
   * releases for latest/notesUrl only, and ends back at "idle".
   */
  async checkNow(): Promise<void> {
    this.state = "checking"
    try {
      await this.checkPrimary()
    } catch (err) {
      // Primary failed (network or parse) → record and try the fallback once.
      this.lastError = errToString(err)
      try {
        await this.checkFallback()
      } catch (fbErr) {
        // Fallback also failed: keep the primary error context; append fallback note.
        this.lastError = `${this.lastError}; fallback: ${errToString(fbErr)}`
      }
    } finally {
      this.lastChecked = Date.now()
      // Only return to idle if the apply engine hasn't moved us elsewhere.
      if (this.state === "checking") this.state = "idle"
    }
  }

  private async checkPrimary(): Promise<void> {
    const headers: Record<string, string> = this.lastEtag
      ? { "if-none-match": this.lastEtag }
      : {}
    const res = await this.fetchImpl(this.url, {
      headers,
      signal: AbortSignal.timeout(FETCH_TIMEOUT_MS),
    })

    if (res.status === 304) {
      // Not modified — keep last good manifest/latest/etag. Clear any stale error.
      this.lastError = null
      return
    }

    if (!res.ok) {
      throw new Error(`versions.json HTTP ${res.status}`)
    }

    const json = await res.json()
    const parsed = parseVersionsJson(json)
    if (!parsed.ok) {
      throw new Error(`versions.json parse failed: ${parsed.error}`)
    }

    // Good parse: store manifest + etag, refresh latest/notesUrl, clear error.
    this.manifest = parsed.data
    const etag = res.headers.get("etag")
    if (etag) this.lastEtag = etag
    this.latest = parsed.data.channels.stable.version
    this.notesUrl = parsed.data.channels.stable.notesUrl
    this.lastError = null
  }

  /**
   * Fallback: GitHub releases/latest. Fills latest + notesUrl ONLY. The manifest
   * deliberately stays null/stale — there are no asset URLs/sha here, so apply
   * must refuse and re-fetch a real versions.json.
   */
  private async checkFallback(): Promise<void> {
    const res = await this.fetchImpl(this.fallbackUrl, {
      headers: {},
      signal: AbortSignal.timeout(FETCH_TIMEOUT_MS),
    })
    if (!res.ok) throw new Error(`fallback HTTP ${res.status}`)
    const body = (await res.json()) as { tag_name?: unknown; html_url?: unknown }
    if (typeof body.tag_name !== "string" || body.tag_name.length === 0) {
      throw new Error("fallback missing tag_name")
    }
    const version = body.tag_name.replace(/^v/, "")
    this.latest = version
    this.notesUrl =
      typeof body.html_url === "string"
        ? body.html_url
        : `https://github.com/UstaLabs/supermux/releases/tag/${body.tag_name}`
    // NOTE: manifest intentionally untouched (no assets/sha from this source).
  }

  /**
   * Begin background polling: a boot-jitter delay, then a fixed interval.
   * No timers run from the constructor so CLI/tests can use checkNow() alone.
   */
  start(): void {
    if (this.bootTimer || this.intervalTimer) return // already started
    // Resolve jitter now (at start time): honor an explicit value (incl. 0),
    // else a fresh random 0–5 min.
    const jitter =
      this.bootJitterMs ?? Math.floor(Math.random() * MAX_BOOT_JITTER_MS)
    this.bootTimer = setTimeout(() => {
      this.bootTimer = null
      void this.checkNow()
      this.intervalTimer = setInterval(() => {
        void this.checkNow()
      }, this.intervalMs)
    }, jitter)
  }

  /** Clear both timers. Safe to call when never started / repeatedly. */
  stop(): void {
    if (this.bootTimer) {
      clearTimeout(this.bootTimer)
      this.bootTimer = null
    }
    if (this.intervalTimer) {
      clearInterval(this.intervalTimer)
      this.intervalTimer = null
    }
  }
}
