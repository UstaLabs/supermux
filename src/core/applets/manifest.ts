// #117: applet.json, the one manifest every applet has, whatever its language. Whatever belongs to the applet's code
// lives here (in git): how to start it, its jobs, which secrets it needs, the shape of its settings. Host state (the
// id, status, secret values, settings values) lives in the broker's database, never in this file.

export interface AppletJob { name: string; every: string; run: string }

export interface AppletConfigField {
  key: string
  label: string
  type: string
  options?: string[]
  default?: unknown
}

export interface AppletManifest {
  title: string
  /** A path inside the applet folder (e.g. public/icon.svg). */
  icon?: string
  /** The start command, any language. None = a static applet: supermux serves public/. */
  run?: string
  /** A path the supervisor may poll once the server is up. */
  health?: string
  jobs: AppletJob[]
  /** Names only; the values are stored by the broker. */
  secrets: string[]
  bin: string[]
  config: AppletConfigField[]
  /** Paths (prefixes) only the owner's devices may reach: an agent gets 403. */
  ownerOnly: string[]
  /** The optional summary a widget shows, e.g. /widget.json. */
  widget?: string
  /** An adopted applet's own data folder, when it is not <folder>/data. */
  data?: string
}

export type ManifestResult = { ok: true; manifest: AppletManifest } | { ok: false; error: string }

/** A name is the folder and the route segment: lowercase letters, digits and dashes, starting with a letter. */
export const APPLET_NAME = /^[a-z][a-z0-9-]{0,47}$/

export function isAppletName(name: string): boolean {
  return APPLET_NAME.test(name) && !name.endsWith("-")
}

/** "30s", "15m", "1h", "1d" → milliseconds, or null. Jobs run no more often than once a minute. */
export function parseEvery(every: string): number | null {
  const m = /^(\d+)\s*(s|m|h|d)$/.exec(every.trim())
  if (!m) return null
  const n = Number(m[1])
  const ms = n * { s: 1_000, m: 60_000, h: 3_600_000, d: 86_400_000 }[m[2] as "s" | "m" | "h" | "d"]
  return ms >= 60_000 ? ms : null
}

/** A relative path that stays inside the folder: no absolute path, no `..` segment. */
function isInsidePath(p: string): boolean {
  if (!p || p.startsWith("/") || p.startsWith("\\") || /^[A-Za-z]:/.test(p)) return false // platform-ok: refusing absolute paths of every OS
  return !p.split(/[\\/]/).includes("..")
}

function strings(v: unknown, field: string): string[] | string {
  if (v === undefined) return []
  if (!Array.isArray(v) || v.some((x) => typeof x !== "string" || !x.trim())) return `"${field}" must be a list of strings`
  return v.map((x: string) => x.trim())
}

/**
 * Reads applet.json's text. Unknown keys are ignored (an agent may add notes, and a newer supermux may add fields), but
 * a known key with the wrong shape is an error the agent sees, so a typo never silently turns a server into a static
 * applet.
 */
export function parseManifest(text: string): ManifestResult {
  let raw: unknown
  try {
    raw = JSON.parse(text)
  } catch (e) {
    return { ok: false, error: `applet.json is not valid JSON: ${(e as Error).message}` }
  }
  if (!raw || typeof raw !== "object" || Array.isArray(raw)) return { ok: false, error: "applet.json must be an object" }
  const o = raw as Record<string, unknown>

  if (typeof o.title !== "string" || !o.title.trim()) return { ok: false, error: `"title" is required` }
  for (const k of ["icon", "run", "health", "widget", "data"] as const) {
    if (o[k] !== undefined && (typeof o[k] !== "string" || !(o[k] as string).trim())) return { ok: false, error: `"${k}" must be a non-empty string` }
  }
  if (typeof o.icon === "string" && !isInsidePath(o.icon)) return { ok: false, error: `"icon" must be a path inside the applet folder` }
  for (const k of ["health", "widget"] as const) {
    if (typeof o[k] === "string" && !(o[k] as string).startsWith("/")) return { ok: false, error: `"${k}" must be a path starting with /` } // platform-ok: URL paths
  }

  const secrets = strings(o.secrets, "secrets")
  if (typeof secrets === "string") return { ok: false, error: secrets }
  const bad = secrets.find((s) => !/^[A-Z_][A-Z0-9_]*$/.test(s))
  if (bad) return { ok: false, error: `secret "${bad}" must be an environment variable name (A-Z, 0-9, _)` }
  const bin = strings(o.bin, "bin")
  if (typeof bin === "string") return { ok: false, error: bin }
  const ownerOnly = strings(o.ownerOnly, "ownerOnly")
  if (typeof ownerOnly === "string") return { ok: false, error: ownerOnly }
  const notPath = ownerOnly.find((p) => !p.startsWith("/")) // platform-ok: URL paths
  if (notPath) return { ok: false, error: `ownerOnly path "${notPath}" must start with /` }

  const jobs: AppletJob[] = []
  if (o.jobs !== undefined) {
    if (!Array.isArray(o.jobs)) return { ok: false, error: `"jobs" must be a list` }
    for (const [i, j] of o.jobs.entries()) {
      if (!j || typeof j !== "object") return { ok: false, error: `jobs[${i}] must be an object` }
      const job = j as Record<string, unknown>
      if (typeof job.run !== "string" || !job.run.trim()) return { ok: false, error: `jobs[${i}].run is required` }
      if (typeof job.every !== "string" || parseEvery(job.every) === null) return { ok: false, error: `jobs[${i}].every must look like "30m", "1h" or "1d" (at least 1m)` }
      const name = typeof job.name === "string" && job.name.trim() ? job.name.trim() : `job${i + 1}`
      if (!/^[a-z0-9][a-z0-9_-]*$/i.test(name)) return { ok: false, error: `jobs[${i}].name must be letters, digits, - or _` }
      if (jobs.some((x) => x.name === name)) return { ok: false, error: `two jobs are named "${name}"` }
      jobs.push({ name, every: job.every.trim(), run: job.run.trim() })
    }
  }

  const config: AppletConfigField[] = []
  if (o.config !== undefined) {
    if (!Array.isArray(o.config)) return { ok: false, error: `"config" must be a list` }
    for (const [i, c] of o.config.entries()) {
      const f = c as Record<string, unknown>
      if (!f || typeof f.key !== "string" || !f.key) return { ok: false, error: `config[${i}].key is required` }
      config.push({
        key: f.key,
        label: typeof f.label === "string" ? f.label : f.key,
        type: typeof f.type === "string" ? f.type : "text",
        ...(Array.isArray(f.options) ? { options: f.options.filter((x): x is string => typeof x === "string") } : {}),
        ...(f.default !== undefined ? { default: f.default } : {}),
      })
    }
  }

  return {
    ok: true,
    manifest: {
      title: o.title.trim(),
      ...(typeof o.icon === "string" ? { icon: o.icon.trim() } : {}),
      ...(typeof o.run === "string" ? { run: o.run.trim() } : {}),
      ...(typeof o.health === "string" ? { health: o.health } : {}),
      ...(typeof o.widget === "string" ? { widget: o.widget } : {}),
      ...(typeof o.data === "string" ? { data: o.data.trim() } : {}),
      jobs, secrets, bin, config, ownerOnly,
    },
  }
}

/**
 * Whether [path] (the part after /a/<name>) falls under one of the manifest's ownerOnly prefixes. Compared the way a
 * lenient server might read it, so an agent can't step around a prefix: percent-decoded, repeated slashes collapsed,
 * any case, and `;params` or a trailing dot cut. Refusing an agent too much is harmless; too little is not.
 */
export function isOwnerOnly(path: string, ownerOnly: string[]): boolean {
  const norm = (s: string) => {
    let p = s.split(/[?#]/)[0]!
    for (let i = 0; i < 3; i++) {
      try {
        const d = decodeURIComponent(p)
        if (d === p) break
        p = d
      } catch {
        break
      }
    }
    return p.replace(/\\/g, "/").replace(/\/{2,}/g, "/").toLowerCase() // platform-ok: URL paths
  }
  const p = norm(path)
  return ownerOnly.some((prefix) => {
    const n = norm(prefix)
    const base = n.endsWith("/") ? n.slice(0, -1) : n // platform-ok: URL paths
    if (p === base || p.startsWith(base + "/")) return true // platform-ok: URL paths
    const rest = p.slice(base.length)
    return p.startsWith(base) && /^(;|\.+($|\/))/.test(rest) // platform-ok: URL paths
  })
}
