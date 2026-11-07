// Antigravity usage. agy's quota sits behind Google's internal API and a login only agy can read and refresh (the
// OS keyring first, a token file as its fallback), so the card asks agy itself: `agy -p /usage --output-format json`
// answers as a command, not a turn: no conversation, no quota spent. From agy 1.1.11 on; before that agy sent it to
// the model as a billed prompt, hence the version check and the latch. agy reports no plan.
//
// A signed-out agy starts a Google sign-in: it opens the browser itself and waits 60 s, whatever --print-timeout
// says. So agy runs with its browser openers stubbed and is stopped the moment it asks; on Windows, where it opens
// the browser through rundll32 and nothing can stand in, agy is not started without its sign-in.

import { existsSync } from "fs"
import { join } from "path"
import { proc } from "@supermux/platform"
import { CoreError } from "../../../packages/supermux-core/src/errors.js"
import { prepareAntigravityEnvironment } from "../../../packages/supermux-core/src/environment/index.js"
import { antigravityUpdaterEnv } from "../../../packages/supermux-core/src/antigravity/updater.js"
import { SIGN_IN_EXPIRED } from "../accounts/broker-accounts"
import { ANTIGRAVITY_SIGN_IN_EXPIRED, browserlessScratch } from "../agents/antigravity/auth"
import { antigravityKeyringLogin } from "../agents/antigravity/keyring"
import { resolveCommand, spawnCommand } from "../process/launcher"
import { compareVersions } from "../update/versions"
import { home } from "../../shared/home"
import { makeLogger } from "../../shared/log"
import { STATE_DIR } from "../../shared/paths"
import { isoFromIsoLike, type AntigravityUsage, type AntigravityUsageWindow } from "./index"

const log = makeLogger("usage")

/** The first agy that answers `-p /usage` as a command; an older one sends it to the model as a billed prompt. */
export const AGY_MIN_USAGE_VERSION = "1.1.11"
const USAGE_ARGS = ["-p", "/usage", "--output-format", "json"]
const VERSION_TIMEOUT_MS = 10_000
const USAGE_TIMEOUT_MS = 30_000
/** agy's line on stderr when it has no login and starts the Google sign-in. */
const SIGN_IN_PROMPT = "Authentication required"
/** After agy exits, how long the rest of its output may take: a process it left running can hold the pipes. */
const EXIT_GRACE_MS = 2_000

const NOT_INSTALLED = "Antigravity (agy) isn't installed on this host."
const API_KEY_ONLY = "Antigravity uses an API key here: billed per token, so there is no usage limit to show."
const VERSION_UNKNOWN = "Couldn't read the agy version — update agy to see usage."
const TIMED_OUT = "Antigravity didn't answer in time — try again in a moment."
const UNREADABLE = "Antigravity didn't report its usage — try again in a moment."
const ASKS_THE_MODEL = "This agy answers /usage as a prompt, which spends quota, so supermux stopped asking. Update agy."

/** The agys (by path) that answered /usage with a model turn: not asked again while the broker runs. */
const askedTheModel = new Set<string>()

// ── agy's reply ──

export type AgyUsageReply =
  | { kind: "usage"; usage: AntigravityUsage }
  | { kind: "turn" }
  | { kind: "error"; message: string }

function record(value: unknown): Record<string, unknown> | null {
  return value !== null && typeof value === "object" && !Array.isArray(value) ? (value as Record<string, unknown>) : null
}

/** Shortest window first, as on the other cards. */
const WINDOW_ORDER: Record<string, number> = { "5h": 0, weekly: 1 }

function bucketWindow(raw: unknown, group: string): { order: number; window: AntigravityUsageWindow } | null {
  const bucket = record(raw)
  // Disabled = unmetered: a bar for it would invent room beside a pool that has none.
  if (!bucket || bucket.disabled === true) return null
  const id = typeof bucket.id === "string" ? bucket.id : ""
  // agy's JSON leaves zero values out, so a bucket with nothing left can arrive without its fraction.
  const left = bucket.remaining_fraction === undefined ? 0 : bucket.remaining_fraction
  if (!id || typeof left !== "number" || !Number.isFinite(left)) return null
  const kind = typeof bucket.window === "string" ? bucket.window : ""
  const name = typeof bucket.name === "string" ? bucket.name.trim() : ""
  const span = kind === "5h" ? "5-hour" : kind === "weekly" ? "weekly" : name || kind || id
  const resetsAt = typeof bucket.reset_time === "string" && bucket.reset_time ? bucket.reset_time : null
  return {
    order: WINDOW_ORDER[kind] ?? 2,
    window: {
      id,
      label: group ? `${group} · ${span}` : span,
      used: Math.min(100, Math.max(0, (1 - left) * 100)),
      resetsAt,
      resetsAtIso: isoFromIsoLike(resetsAt),
    },
  }
}

/** The windows in a `-p /usage` envelope (`/quota` answers as usage too); null for any other envelope. */
function usageOf(envelope: Record<string, unknown>): AntigravityUsage | null {
  const command = record(envelope.command)
  const data = record(command?.data)
  if (envelope.status !== "SUCCESS" || command?.name !== "usage" || !data || !Array.isArray(data.groups)) return null
  const windows: AntigravityUsageWindow[] = []
  for (const raw of data.groups) {
    const group = record(raw)
    if (!group) continue
    const name = typeof group.name === "string" ? group.name.trim() : ""
    const drawn = (Array.isArray(group.buckets) ? group.buckets : []).flatMap((b) => bucketWindow(b, name) ?? [])
    drawn.sort((a, b) => a.order - b.order)
    windows.push(...drawn.map((d) => d.window))
  }
  return { windows }
}

/** agy's answer to `-p /usage --output-format json`, found among whatever else it printed; null when there is none. */
export function parseAgyUsageReply(stdout: string): AgyUsageReply | null {
  let turn = false
  let error: string | null = null
  for (const line of stdout.split(/\r?\n/)) {
    const text = line.trim()
    if (!text.startsWith("{")) continue
    let envelope: Record<string, unknown> | null
    try {
      envelope = record(JSON.parse(text))
    } catch {
      continue
    }
    if (!envelope) continue
    const usage = usageOf(envelope)
    if (usage) return { kind: "usage", usage }
    const turns = envelope.num_turns
    if ((typeof turns === "number" && turns > 0) || (typeof envelope.conversation_id === "string" && envelope.conversation_id !== "")) turn = true
    if (envelope.status === "ERROR" && typeof envelope.error === "string") {
      const words = envelope.error.replace(/\s+/g, " ").trim().slice(0, 300)
      if (words) error ??= words
    }
  }
  if (turn) return { kind: "turn" }
  if (error) return { kind: "error", message: error }
  return null
}

// ── running agy ──

/** One agy run: what it printed, and whether it was stopped (at the timeout, or as it asked for a sign-in). */
export interface AgyRun {
  /** The exit code; null when agy was stopped or could not start. */
  code: number | null
  stdout: string
  stderr: string
  timedOut: boolean
  /** agy had no usable login and asked for a Google sign-in, and was stopped there. */
  signInPrompt: boolean
}

export interface AgyRunOptions {
  env: Record<string, string>
  cwd: string
  timeoutMs: number
  /** The OS whose rules stop agy; the running one by default. */
  platform?: NodeJS.Platform
}

/** Runs agy, and stops it the moment it asks for a sign-in (it would open the browser and wait 60 s) or at the timeout. */
export function runAgy(command: string, args: readonly string[], opts: AgyRunOptions): Promise<AgyRun> {
  return new Promise((resolve) => {
    const out = { stdout: "", stderr: "", timedOut: false, signInPrompt: false }
    const os = opts.platform ? { platform: opts.platform } : {}
    let child: ReturnType<typeof spawnCommand>
    try {
      child = spawnCommand(command, args, { env: opts.env, cwd: opts.cwd, stdio: ["ignore", "pipe", "pipe"], ...os })
    } catch (err) {
      resolve({ code: null, ...out, stderr: String(err) })
      return
    }
    let settled = false
    let grace: ReturnType<typeof setTimeout> | undefined
    const stop = () => {
      if (child.pid !== undefined) proc.killTree(child.pid, os)
      else child.kill()
    }
    const timer = setTimeout(() => {
      out.timedOut = true
      stop()
    }, opts.timeoutMs)
    const finish = (code: number | null) => {
      if (settled) return
      settled = true
      clearTimeout(timer)
      if (grace) clearTimeout(grace)
      resolve({ code: out.timedOut || out.signInPrompt ? null : code, ...out })
    }
    child.stdout?.setEncoding("utf8")
    child.stderr?.setEncoding("utf8")
    child.stdout?.on("data", (chunk: string) => { out.stdout += chunk })
    child.stderr?.on("data", (chunk: string) => {
      out.stderr += chunk
      if (!out.signInPrompt && out.stderr.includes(SIGN_IN_PROMPT)) {
        out.signInPrompt = true
        stop()
      }
    })
    child.once("error", (err) => {
      out.stderr += String(err)
      finish(null)
    })
    child.once("close", (code) => finish(code))
    child.once("exit", (code) => { grace = setTimeout(() => finish(code), EXIT_GRACE_MS) })
  })
}

// ── the usage ──

export interface AntigravityUsageDeps {
  /** The OS whose rules apply; the running one by default. */
  platform?: NodeJS.Platform
  /** The environment the system login's sessions get; process.env by default. */
  env?: Record<string, string | undefined>
  userHome?: string
  stateDir?: string
  /** Finds agy; on the env's PATH by default. */
  resolve?: (env: Record<string, string>) => string | null
  /** Windows: whether Credential Manager holds agy's login. */
  windowsLogin?: () => boolean
  run?: (command: string, args: readonly string[], opts: AgyRunOptions) => Promise<AgyRun>
  /** The agys that answered /usage with a model turn; the broker's own set by default. */
  latch?: Set<string>
}

function defined(env: Record<string, string | undefined>): Record<string, string> {
  return Object.fromEntries(Object.entries(env).filter((e): e is [string, string] => e[1] !== undefined))
}

/**
 * The system login's Antigravity usage, read by asking agy (see the top of this file). Null on Windows when agy has
 * no sign-in there. Throws in the words the card shows: agy missing, too old, answering with a model turn, silent
 * past the timeout, or its own error. A sign-in agy asks for is CoreError SIGN_IN_EXPIRED, unless the login is an
 * API key, which has no limit to show.
 */
export async function fetchAntigravityUsage(deps: AntigravityUsageDeps = {}): Promise<AntigravityUsage | null> {
  // platform-ok: agy opens the browser itself, through PATH openers off Windows and rundll32 on it (#103)
  const platform = deps.platform ?? process.platform
  const env = defined(deps.env ?? process.env)
  const command = deps.resolve ? deps.resolve(env) : resolveCommand(["agy"], env, platform)
  if (!command) throw new Error(NOT_INSTALLED)
  const apiKey = !!proc.envValue(env, "GEMINI_API_KEY", platform)
  const userHome = deps.userHome ?? home()
  const userCli = join(userHome, ".gemini", "antigravity-cli")
  // agy makes its folder the first time it runs, so without one there is no sign-in (and agy could not start in the
  // shadow HOME, whose link to the missing folder stops it making its own). Windows: no start without the sign-in.
  const signedIn = existsSync(userCli) && (platform !== "win32"
    || (deps.windowsLogin ?? (() => antigravityKeyringLogin("win32")))()
    || existsSync(join(userCli, "antigravity-oauth-token")))
  if (!signedIn) {
    if (apiKey) throw new Error(API_KEY_ONLY)
    return null
  }
  const latch = deps.latch ?? askedTheModel
  if (latch.has(command)) throw new Error(ASKS_THE_MODEL)

  // Its own shadow HOME: never the user's, and not the model list's, which a concurrent `agy models` rebuilds.
  const shadow = join(deps.stateDir ?? STATE_DIR, "agents", "antigravity", "_usage", "system", "home")
  const prepared = await prepareAntigravityEnvironment({ home: shadow, userHome, platform })
  const scratch = browserlessScratch({ ...env, ...prepared.env, ...antigravityUpdaterEnv(platform) }, platform)
  const run = deps.run ?? runAgy
  const options = (timeoutMs: number): AgyRunOptions => ({ env: scratch.env, cwd: scratch.cwd, timeoutMs, platform })
  try {
    const answer = await run(command, ["--version"], options(VERSION_TIMEOUT_MS))
    const version = answer.code === 0 ? /\d+\.\d+\.\d+/.exec(`${answer.stdout}\n${answer.stderr}`)?.[0] : undefined
    if (!version) throw new Error(VERSION_UNKNOWN)
    if (compareVersions(version, AGY_MIN_USAGE_VERSION) < 0) {
      throw new Error(`Antigravity usage needs agy ${AGY_MIN_USAGE_VERSION} or newer; this host has ${version}. Update agy.`)
    }

    const result = await run(command, USAGE_ARGS, options(USAGE_TIMEOUT_MS))
    const reply = parseAgyUsageReply(result.stdout)
    if (reply?.kind === "usage") return reply.usage
    if (result.signInPrompt) {
      if (apiKey) throw new Error(API_KEY_ONLY)
      throw new CoreError(SIGN_IN_EXPIRED, ANTIGRAVITY_SIGN_IN_EXPIRED)
    }
    if (result.timedOut) throw new Error(TIMED_OUT)
    if (reply?.kind === "turn") {
      latch.add(command)
      throw new Error(ASKS_THE_MODEL)
    }
    if (reply?.kind === "error") throw new Error(`Antigravity: ${reply.message}`)
    log.warn("antigravity_usage_unreadable", { code: result.code, stderr: result.stderr.slice(-300) })
    throw new Error(UNREADABLE)
  } finally {
    scratch.cleanup()
  }
}
