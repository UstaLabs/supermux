import { afterEach, beforeEach, expect, test } from "bun:test"
import { existsSync, mkdirSync, mkdtempSync, readdirSync, rmSync, writeFileSync } from "fs"
import { tmpdir } from "os"
import { delimiter, join } from "path"
import { CoreError } from "../../../packages/supermux-core/src/errors.js"
import { SIGN_IN_EXPIRED } from "../accounts/broker-accounts"
import {
  AGY_MIN_USAGE_VERSION,
  fetchAntigravityUsage,
  parseAgyUsageReply,
  runAgy,
  type AgyRun,
  type AntigravityUsageDeps,
} from "./antigravity"

// What `agy -p /usage --output-format json` printed on agy 1.5.3 (2026-11-07), trimmed.
function reply(groups: unknown[], extra: Record<string, unknown> = {}): string {
  return JSON.stringify({
    conversation_id: "",
    status: "SUCCESS",
    response: "Gemini Models\tWeekly Limit Remaining\t100%\t2026-11-14T12:19:08Z\n",
    duration_seconds: 0,
    num_turns: 0,
    usage: { input_tokens: 0, output_tokens: 0, thinking_tokens: 0, cache_read_tokens: 0, total_tokens: 0 },
    command: { name: "usage", data: { description: "Within each group, models share a weekly limit and a 5-hour limit.", groups } },
    ...extra,
  })
}

const GROUPS = [
  {
    name: "Gemini Models",
    description: "Models within this group: Gemini Flash, Gemini Pro",
    buckets: [
      { id: "gemini-weekly", name: "Weekly Limit Remaining", window: "weekly", remaining_fraction: 0.75, reset_time: "2026-11-14T12:19:08Z" },
      { id: "gemini-5h", name: "Five Hour Limit Remaining", window: "5h", remaining_fraction: 0.4, reset_time: "2026-11-07T17:19:08Z" },
    ],
  },
  {
    name: "Claude and GPT models",
    buckets: [
      { id: "3p-weekly", name: "Weekly Limit Remaining", window: "weekly", remaining_fraction: 1, reset_time: "2026-11-14T12:41:45Z" },
      { id: "3p-5h", name: "Five Hour Limit Remaining", window: "5h", remaining_fraction: 1, reset_time: "2026-11-07T17:41:45Z" },
    ],
  },
  {
    name: "Gemini 4 Argon",
    buckets: [
      { id: "gemini-argon-wtu-weekly", name: "Gemini 4 Argon Weekly Limit", window: "weekly", remaining_fraction: 1, reset_time: "2026-11-14T12:41:45Z" },
    ],
  },
]

// ── the reply ──

test("each of agy's buckets becomes a window: used is what is gone, labelled by group and window, 5-hour first", () => {
  const parsed = parseAgyUsageReply(reply(GROUPS))
  expect(parsed?.kind).toBe("usage")
  if (parsed?.kind !== "usage") return
  expect(parsed.usage.windows.map((w) => [w.id, w.label])).toEqual([
    ["gemini-5h", "Gemini Models · 5-hour"],
    ["gemini-weekly", "Gemini Models · weekly"],
    ["3p-5h", "Claude and GPT models · 5-hour"],
    ["3p-weekly", "Claude and GPT models · weekly"],
    ["gemini-argon-wtu-weekly", "Gemini 4 Argon · weekly"],
  ])
  const gemini5h = parsed.usage.windows[0]!
  expect(gemini5h.used).toBeCloseTo(60, 6)
  expect(gemini5h.resetsAt).toBe("2026-11-07T17:19:08Z")
  expect(gemini5h.resetsAtIso).toBe("2026-11-07T17:19:08.000Z")
  expect(parsed.usage.windows[1]!.used).toBeCloseTo(25, 6)
  expect(parsed.usage.windows[2]!.used).toBe(0)
})

test("a bucket without remaining_fraction is used up: agy's JSON leaves a zero out", () => {
  const parsed = parseAgyUsageReply(reply([
    { name: "Gemini Models", buckets: [{ id: "gemini-5h", name: "Five Hour Limit Remaining", window: "5h", reset_time: "2026-11-07T17:19:08Z" }] },
  ]))
  if (parsed?.kind !== "usage") throw new Error(`expected usage, got ${JSON.stringify(parsed)}`)
  expect(parsed.usage.windows).toEqual([
    { id: "gemini-5h", label: "Gemini Models · 5-hour", used: 100, resetsAt: "2026-11-07T17:19:08Z", resetsAtIso: "2026-11-07T17:19:08.000Z" },
  ])
})

test("a disabled (unmetered) bucket and one whose fraction is not a number are left out; used stays within 0..100", () => {
  const parsed = parseAgyUsageReply(reply([
    {
      name: "Gemini Models",
      buckets: [
        { id: "gemini-weekly", window: "weekly", remaining_fraction: 0.5, disabled: true },
        { id: "gemini-5h", window: "5h", remaining_fraction: "half" },
        { id: "over", window: "5h", remaining_fraction: 1.2 },
        { id: "under", window: "weekly", remaining_fraction: -0.5 },
        { window: "5h", remaining_fraction: 0.5 },
      ],
    },
  ]))
  if (parsed?.kind !== "usage") throw new Error(`expected usage, got ${JSON.stringify(parsed)}`)
  expect(parsed.usage.windows.map((w) => [w.id, w.used])).toEqual([["over", 0], ["under", 100]])
  expect(parsed.usage.windows[0]!.resetsAt).toBeNull()
  expect(parsed.usage.windows[0]!.resetsAtIso).toBeNull()
})

test("a window agy names that is neither 5h nor weekly keeps agy's own bucket name, after the known ones", () => {
  const parsed = parseAgyUsageReply(reply([
    {
      name: "Gemini Models",
      buckets: [
        { id: "gemini-daily", name: "Daily Limit Remaining", window: "daily", remaining_fraction: 0.9 },
        { id: "gemini-weekly", name: "Weekly Limit Remaining", window: "weekly", remaining_fraction: 0.9 },
      ],
    },
  ]))
  if (parsed?.kind !== "usage") throw new Error(`expected usage, got ${JSON.stringify(parsed)}`)
  expect(parsed.usage.windows.map((w) => w.label)).toEqual(["Gemini Models · weekly", "Gemini Models · Daily Limit Remaining"])
})

test("the reply is found among other lines agy prints", () => {
  const out = ["Fetching quota...", "{not json", JSON.stringify({ event: "noise" }), reply(GROUPS), ""].join("\n")
  expect(parseAgyUsageReply(out)?.kind).toBe("usage")
})

test("a reply that ran a model turn reads as a turn, never as usage", () => {
  const prompt = JSON.stringify({ conversation_id: "c-1", status: "SUCCESS", response: "Your usage is fine.", num_turns: 1 })
  expect(parseAgyUsageReply(prompt)).toEqual({ kind: "turn" })
  expect(parseAgyUsageReply(JSON.stringify({ conversation_id: "", status: "SUCCESS", response: "Sure.", num_turns: 2 }))).toEqual({ kind: "turn" })
})

test("agy's ERROR envelope carries its words, on one line", () => {
  const out = JSON.stringify({ conversation_id: "", status: "ERROR", response: "", error: "authentication failed\n  or timed out", num_turns: 0 })
  expect(parseAgyUsageReply(out)).toEqual({ kind: "error", message: "authentication failed or timed out" })
})

test("no reply at all is null", () => {
  expect(parseAgyUsageReply("")).toBeNull()
  expect(parseAgyUsageReply("panic: something\n")).toBeNull()
  expect(parseAgyUsageReply(JSON.stringify({ status: "SUCCESS", command: { name: "credits", data: { remaining_credits: 0 } } }))).toBeNull()
})

// ── asking agy ──

let tmp: string
let stateDir: string
let userHome: string
let userCli: string

beforeEach(() => {
  tmp = mkdtempSync(join(tmpdir(), "agy-usage-"))
  stateDir = join(tmp, "state")
  userHome = join(tmp, "user")
  userCli = join(userHome, ".gemini", "antigravity-cli")
  mkdirSync(userCli, { recursive: true })
})

afterEach(() => rmSync(tmp, { recursive: true, force: true }))

type Call = { command: string; args: string[]; env: Record<string, string>; cwd: string; cwdEntries: string[]; pathHeadEntries: string[] | null }

function fakeAgy(answers: { version?: Partial<AgyRun>; usage?: Partial<AgyRun> }): { calls: Call[]; run: NonNullable<AntigravityUsageDeps["run"]> } {
  const calls: Call[] = []
  const done = (r: Partial<AgyRun> | undefined): AgyRun => ({ code: 0, stdout: "", stderr: "", timedOut: false, signInPrompt: false, ...r })
  return {
    calls,
    run: async (command, args, opts) => {
      // What the folders hold while agy runs: the scratch folder is gone once the read ends.
      const head = (opts.env.PATH ?? "").split(delimiter)[0]
      const pathHeadEntries = head && existsSync(head) ? readdirSync(head).sort() : null
      calls.push({ command, args: [...args], env: { ...opts.env }, cwd: opts.cwd, cwdEntries: readdirSync(opts.cwd), pathHeadEntries })
      return args[0] === "--version" ? done(answers.version ?? { stdout: "1.5.3\n" }) : done(answers.usage)
    },
  }
}

const SHADOW = () => join(stateDir, "agents", "antigravity", "_usage", "system", "home")

// The host's own OS, so the shadow HOME is built with links this OS can make; the Windows sign-in check passes.
function deps(over: Partial<AntigravityUsageDeps>): AntigravityUsageDeps {
  return {
    platform: process.platform,
    windowsLogin: () => true,
    env: { PATH: "/usr/bin", DISPLAY: ":0", WAYLAND_DISPLAY: "wayland-0" },
    userHome,
    stateDir,
    resolve: () => "/opt/agy/bin/agy",
    latch: new Set(),
    ...over,
  }
}

test.skipIf(process.platform === "win32")("usage is read through agy itself, under a shadow HOME, in an empty folder, with the browser openers stubbed", async () => {
  const agy = fakeAgy({ usage: { stdout: reply(GROUPS) } })
  const usage = await fetchAntigravityUsage(deps({ platform: "linux", run: agy.run }))

  expect(usage?.windows.map((w) => w.id)).toEqual(["gemini-5h", "gemini-weekly", "3p-5h", "3p-weekly", "gemini-argon-wtu-weekly"])
  expect(agy.calls.map((c) => c.args)).toEqual([["--version"], ["-p", "/usage", "--output-format", "json"]])
  const call = agy.calls[1]!
  expect(call.command).toBe("/opt/agy/bin/agy")
  expect(call.env.HOME).toBe(SHADOW())
  // agy calls xdg-open / open itself; BROWSER and DISPLAY do not stop it.
  const bin = call.env.PATH!.split(delimiter)[0]!
  expect(call.pathHeadEntries).toEqual(["open", "xdg-open"])
  expect(call.env.PATH!.endsWith(`${delimiter}/usr/bin`)).toBe(true)
  expect(call.env.BROWSER).toBe("true")
  expect(call.env.DISPLAY).toBeUndefined()
  expect(call.env.WAYLAND_DISPLAY).toBeUndefined()
  // Off Windows agy's updater is silent and keeps agy current (#70).
  expect(call.env.AGY_CLI_DISABLE_AUTO_UPDATE).toBeUndefined()
  expect(call.cwdEntries).toEqual([])
  expect(call.cwd.startsWith(tmpdir())).toBe(true)
  // The scratch folder goes, and the real ~/.gemini is never written.
  expect(existsSync(call.cwd)).toBe(false)
  expect(existsSync(bin)).toBe(false)
  expect(readdirSync(join(userHome, ".gemini"))).toEqual(["antigravity-cli"])
  expect(readdirSync(userCli)).toEqual([])
})

test("an agy that never ran here (no ~/.gemini/antigravity-cli) is not signed in, and is not started", async () => {
  rmSync(join(userHome, ".gemini"), { recursive: true, force: true })
  const agy = fakeAgy({ usage: { stdout: reply(GROUPS) } })
  expect(await fetchAntigravityUsage(deps({ run: agy.run }))).toBeNull()
  await expect(fetchAntigravityUsage(deps({ run: agy.run, env: { PATH: "/usr/bin", GEMINI_API_KEY: "k" } }))).rejects.toThrow(
    "Antigravity uses an API key here: billed per token, so there is no usage limit to show.",
  )
  expect(agy.calls).toEqual([])
})

test("without agy installed it says so and runs nothing", async () => {
  const agy = fakeAgy({})
  await expect(fetchAntigravityUsage(deps({ run: agy.run, resolve: () => null }))).rejects.toThrow("Antigravity (agy) isn't installed on this host.")
  expect(agy.calls).toEqual([])
})

test(`an agy older than ${AGY_MIN_USAGE_VERSION} is never asked: it would send /usage to the model as a billed prompt`, async () => {
  const agy = fakeAgy({ version: { stdout: "agy 1.1.10\n" } })
  await expect(fetchAntigravityUsage(deps({ run: agy.run }))).rejects.toThrow(
    "Antigravity usage needs agy 1.1.11 or newer; this host has 1.1.10. Update agy.",
  )
  expect(agy.calls.map((c) => c.args)).toEqual([["--version"]])
})

test("an agy whose version can't be read is not asked either", async () => {
  const agy = fakeAgy({ version: { code: 1, stdout: "", stderr: "boom" } })
  await expect(fetchAntigravityUsage(deps({ run: agy.run }))).rejects.toThrow("Couldn't read the agy version — update agy to see usage.")
  expect(agy.calls.map((c) => c.args)).toEqual([["--version"]])
})

test("agy asking for a sign-in is an expired sign-in (account_expired), in plain words", async () => {
  const agy = fakeAgy({ usage: { code: null, signInPrompt: true, stderr: "Authentication required. Please visit the URL to log in:\n" } })
  const err = await fetchAntigravityUsage(deps({ run: agy.run })).catch((e) => e)
  expect(err).toBeInstanceOf(CoreError)
  expect((err as CoreError).code).toBe(SIGN_IN_EXPIRED)
  expect((err as CoreError).message).toBe("Antigravity sign-in expired — sign in to Antigravity again.")
})

test("with an API key and no Google sign-in, the sign-in prompt means no limit to show, not an expired sign-in", async () => {
  const agy = fakeAgy({ usage: { code: null, signInPrompt: true } })
  const err = await fetchAntigravityUsage(deps({ run: agy.run, env: { PATH: "/usr/bin", GEMINI_API_KEY: "k" } })).catch((e) => e)
  expect(err).not.toBeInstanceOf(CoreError)
  expect((err as Error).message).toBe("Antigravity uses an API key here: billed per token, so there is no usage limit to show.")
})

test("an agy that doesn't answer in time reads as a try-again, not a spinner", async () => {
  const agy = fakeAgy({ usage: { code: null, timedOut: true } })
  await expect(fetchAntigravityUsage(deps({ run: agy.run }))).rejects.toThrow("Antigravity didn't answer in time — try again in a moment.")
})

test("an agy that answers /usage with a model turn is never asked again", async () => {
  const latch = new Set<string>()
  const turn = fakeAgy({ usage: { stdout: JSON.stringify({ conversation_id: "c-1", status: "SUCCESS", response: "Fine.", num_turns: 1 }) } })
  const stopped = "This agy answers /usage as a prompt, which spends quota, so supermux stopped asking. Update agy."
  await expect(fetchAntigravityUsage(deps({ run: turn.run, latch }))).rejects.toThrow(stopped)

  const next = fakeAgy({ usage: { stdout: reply(GROUPS) } })
  await expect(fetchAntigravityUsage(deps({ run: next.run, latch }))).rejects.toThrow(stopped)
  expect(next.calls).toEqual([])
})

test("agy's own error reads in agy's words", async () => {
  const agy = fakeAgy({ usage: { code: 1, stdout: JSON.stringify({ conversation_id: "", status: "ERROR", error: "quota service unavailable", num_turns: 0 }) } })
  await expect(fetchAntigravityUsage(deps({ run: agy.run }))).rejects.toThrow("Antigravity: quota service unavailable")
})

test("an answer with no usage in it is a plain try-again", async () => {
  const agy = fakeAgy({ usage: { code: 2, stdout: "", stderr: "flag provided but not defined" } })
  await expect(fetchAntigravityUsage(deps({ run: agy.run }))).rejects.toThrow("Antigravity didn't report its usage — try again in a moment.")
})

test("Windows: agy is never started without its sign-in, since it would open the browser there: not signed in", async () => {
  const agy = fakeAgy({ usage: { stdout: reply(GROUPS) } })
  expect(await fetchAntigravityUsage(deps({ platform: "win32", run: agy.run, windowsLogin: () => false }))).toBeNull()
  expect(agy.calls).toEqual([])
})

test("Windows: an API key and no sign-in reads as no limit to show, without starting agy", async () => {
  const agy = fakeAgy({})
  await expect(
    fetchAntigravityUsage(deps({ platform: "win32", run: agy.run, windowsLogin: () => false, env: { Path: "C:\\bin", GEMINI_API_KEY: "k" } })),
  ).rejects.toThrow("Antigravity uses an API key here: billed per token, so there is no usage limit to show.")
  expect(agy.calls).toEqual([])
})

test("Windows: with the Credential Manager login it asks agy, updater off and no PATH openers", async () => {
  const agy = fakeAgy({ usage: { stdout: reply(GROUPS) } })
  const usage = await fetchAntigravityUsage(deps({ platform: "win32", run: agy.run, windowsLogin: () => true, env: { Path: "C:\\bin" } }))
  expect(usage?.windows).toHaveLength(5)
  const call = agy.calls[1]!
  expect(call.env.USERPROFILE).toBe(SHADOW())
  expect(call.env.AGY_CLI_DISABLE_AUTO_UPDATE).toBe("true")
  expect(call.env.Path).toBe("C:\\bin")
  expect(call.env.PATH).toBeUndefined()
  expect(call.env.BROWSER).toBeUndefined()
})

test("Windows: agy's token file counts as a sign-in too", async () => {
  writeFileSync(join(userCli, "antigravity-oauth-token"), "{}")
  const agy = fakeAgy({ usage: { stdout: reply(GROUPS) } })
  expect((await fetchAntigravityUsage(deps({ platform: "win32", run: agy.run, windowsLogin: () => false })))?.windows).toHaveLength(5)
})

// ── the process ──

function script(body: string): string {
  const path = join(tmp, "fake-agy.js")
  writeFileSync(path, body)
  return path
}

test("agy is stopped the moment it asks for a sign-in, not after its 60 s wait", async () => {
  const path = script(`
    process.stderr.write("Authentication required. Please visit the URL to log in:\\n  https://accounts.google.com/o/oauth2/auth?x=1\\n")
    setTimeout(() => {}, 60000)
  `)
  const started = Date.now()
  const run = await runAgy(process.execPath, [path], { env: { ...process.env } as Record<string, string>, cwd: tmp, timeoutMs: 30_000 })
  expect(run.signInPrompt).toBe(true)
  expect(run.timedOut).toBe(false)
  expect(Date.now() - started).toBeLessThan(10_000)
})

test("agy is stopped at the timeout", async () => {
  const path = script(`setTimeout(() => {}, 60000)`)
  const started = Date.now()
  const run = await runAgy(process.execPath, [path], { env: { ...process.env } as Record<string, string>, cwd: tmp, timeoutMs: 500 })
  expect(run.timedOut).toBe(true)
  expect(run.signInPrompt).toBe(false)
  expect(Date.now() - started).toBeLessThan(10_000)
})

test("what agy printed comes back whole when it exits", async () => {
  const big = "x".repeat(200_000)
  const path = script(`process.stdout.write(${JSON.stringify(big)} + "\\n"); process.stderr.write("note\\n"); process.exitCode = 3`)
  const run = await runAgy(process.execPath, [path], { env: { ...process.env } as Record<string, string>, cwd: tmp, timeoutMs: 30_000 })
  expect(run).toEqual({ code: 3, stdout: `${big}\n`, stderr: "note\n", timedOut: false, signInPrompt: false })
})

test("an agy that can't be started is an answer too, not a crash", async () => {
  const run = await runAgy(join(tmp, "no-such-agy"), [], { env: { ...process.env } as Record<string, string>, cwd: tmp, timeoutMs: 30_000 })
  expect(run.code).toBeNull()
  expect(run.timedOut).toBe(false)
  expect(run.stdout).toBe("")
})
