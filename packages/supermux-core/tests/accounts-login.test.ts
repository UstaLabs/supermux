import { afterEach, expect, setDefaultTimeout, test } from "bun:test"
import { chmod, lstat, mkdir, mkdtemp, readdir, readFile, readlink, rm, writeFile } from "node:fs/promises"
import { tmpdir } from "node:os"
import { join } from "node:path"
import { createCore, memoryVault } from "../src/index.js"
import { parseDeviceAuth, parseLoginUrl, ptyCommand } from "../src/accounts/index.js"
import type { AgentDriver, LoginHandle, LoginRunner, LoginState } from "../src/index.js"
import { TEST_LIMITS } from "./helpers.js"

setDefaultTimeout(20_000)
const FIXTURE = join(import.meta.dir, "fixtures", "login-cli.mjs")
const dirs: string[] = []
const cores: ReturnType<typeof createCore>[] = []
afterEach(async () => {
  await Promise.all(cores.splice(0).map(core => core.close({ agents: "shutdown" }).catch(() => {})))
  await Promise.all(dirs.splice(0).map(dir => rm(dir, { recursive: true, force: true })))
})

const driver = (id: string): AgentDriver => ({ id, async open() { throw new Error("not used") } })
type Identity = { email?: string; org?: string; accountId?: string }
const IDENTITY: Identity = { email: "new@x.io", org: "org-new", accountId: "acct-new" }

async function setup(options: { behavior?: string; identity?: Identity; timeoutMs?: number; runner?: LoginRunner } = {}) {
  const base = await mkdtemp(join(tmpdir(), "accounts-login-"))
  dirs.push(base)
  const log = join(base, "cli.log")
  await writeFile(log, "")
  const commands: Record<string, string> = {}
  for (const kind of ["claude", "codex", "grok", "cursor"]) {
    const path = join(base, `fake-${kind}`)
    await writeFile(path, `#!/bin/sh\nexec ${JSON.stringify(process.execPath)} ${JSON.stringify(FIXTURE)} ${kind} ${options.behavior ?? "ok"} ${JSON.stringify(log)} '${JSON.stringify(options.identity ?? IDENTITY)}' -- "$@"\n`)
    await chmod(path, 0o755)
    commands[kind] = path
  }
  const roots = { claudeRoot: join(base, "claude-root"), codexRoot: join(base, "codex-root"), grokRoot: join(base, "grok-root"), cursorRoot: join(base, "cursor-root") }
  const stateDirectory = join(base, "state")
  const vault = memoryVault()
  const core = createCore({
    stateDirectory, agents: ["claude", "codex", "grok", "cursor", "opencode"].map(driver), limits: TEST_LIMITS,
    accounts: { vault, homes: roots, login: { commands, ...(options.timeoutMs ? { timeoutMs: options.timeoutMs } : {}), ...(options.runner ? { runner: options.runner } : {}) } },
  })
  cores.push(core)
  const pending = join(stateDirectory, "accounts", "pending")
  const invocations = async () => (await readFile(log, "utf8")).split("\n").filter(Boolean).map(line => JSON.parse(line))
  return { core, base, roots, stateDirectory, vault, pending, invocations }
}

function phase(handle: LoginHandle, wanted: LoginState["phase"], ms = 10_000): Promise<LoginState> {
  return new Promise((resolve, reject) => {
    const check = (state: LoginState) => { if (state.phase === wanted) { off(); clearTimeout(timer); resolve(state) } }
    const off = handle.on(check)
    const timer = setTimeout(() => { off(); reject(new Error(`timed out waiting for ${wanted}; at ${JSON.stringify(handle.state())}`)) }, ms)
    check(handle.state())
  })
}

async function empty(path: string): Promise<boolean> {
  try { return (await readdir(path)).length === 0 } catch { return true }
}

const alive = (pid: number) => { try { process.kill(pid, 0); return true } catch { return false } }
/** A killed child can stay visible for a moment until it is reaped (loaded machines). */
const gone = async (pid: number) => { for (let i = 0; i < 40 && alive(pid); i++) await Bun.sleep(50); return !alive(pid) }

test("parsers strip ANSI/OSC 8 and find URLs and device codes", () => {
  const url = "https://claude.ai/oauth/authorize?code=true&x=1"
  expect(parseLoginUrl(`\x1b]8;;${url}\x07${url}\x1b]8;;\x07`)).toBe(url)
  expect(parseDeviceAuth("Open \x1b[94mhttps://auth.openai.com/codex/device\x1b[0m code \x1b[94mABCD-EFGH\x1b[0m")).toEqual({ url: "https://auth.openai.com/codex/device", code: "ABCD-EFGH" })
  expect(parseDeviceAuth("https://auth.openai.com/codex/device only")).toBeUndefined()
  expect(ptyCommand({ command: "/x/claude", args: ["auth", "login", "--email", "a'b@x"] }, "linux")).toEqual({ command: "script", args: ["-qec", `stty cols 600; exec '/x/claude' 'auth' 'login' '--email' 'a'\\''b@x'`, "/dev/null"] })
  expect(ptyCommand({ command: "claude", args: [] }, "darwin").command).toBe("/bin/sh")
  expect(() => ptyCommand({ command: "claude", args: [] }, "win32")).toThrow()
})

test("claude: PTY login, URL parsed, code typed then Enter, promoted into an account home with shared links", async () => {
  const { core, roots, stateDirectory, pending, invocations } = await setup()
  process.env.ANTHROPIC_API_KEY = "must-not-leak"
  let handle: LoginHandle
  try { handle = core.accounts.login({ agent: "claude", id: "cl", label: "Work", email: "new@x.io" }) } finally { delete process.env.ANTHROPIC_API_KEY }
  const waiting = await phase(handle, "awaiting_user")
  expect(waiting).toMatchObject({ phase: "awaiting_user", url: "https://claude.ai/oauth/authorize?code=true&client_id=fake&state=s1", needsCode: true })
  expect(waiting.code).toBeUndefined()
  handle.submitCode("  GOOD-CODE\n")
  const account = await handle.done
  expect(account).toMatchObject({ id: "cl", agent: "claude", method: "subscription", label: "Work", identity: { email: "new@x.io", org: "org-new", accountId: "acct-new" } })
  expect(handle.state().phase).toBe("done")
  const [run, typed] = await invocations()
  expect(run.tty).toBe(true)
  expect(run.leaked).toEqual([])
  expect(run.args).toEqual(["auth", "login", "--claudeai", "--email", "new@x.io"])
  expect(run.env.CLAUDE_CONFIG_DIR.startsWith(pending + "/")).toBe(true)
  expect(typed).toEqual({ code: "GOOD-CODE" })
  const home = join(stateDirectory, "accounts", "homes", "claude", "cl")
  expect(JSON.parse(await readFile(join(home, ".credentials.json"), "utf8")).claudeAiOauth).toBeDefined()
  expect((await lstat(join(home, "projects"))).isSymbolicLink()).toBe(true)
  expect(await readlink(join(home, "projects"))).toBe(join(roots.claudeRoot, "projects"))
  // A non-empty settings.json left by the login run does not block the shared link.
  expect(await readlink(join(home, "settings.json"))).toBe(join(roots.claudeRoot, "settings.json"))
  expect(((await lstat(home)).mode & 0o777)).toBe(0o700)
  expect(await empty(pending)).toBe(true)
  expect((await core.accounts.get("cl"))?.method).toBe("subscription")
})

test("codex: device URL + code; subscription home keeps auth.json; env stripped; store forced to file", async () => {
  const { core, stateDirectory, pending, invocations } = await setup()
  process.env.OPENAI_API_KEY = "must-not-leak"
  let handle: LoginHandle
  try { handle = core.accounts.login({ agent: "codex" }) } finally { delete process.env.OPENAI_API_KEY }
  const states: LoginState[] = []
  handle.on(state => states.push(state))
  const account = await handle.done
  expect(states.map(s => s.phase)).toContain("verifying")
  expect(states.find(s => s.phase === "awaiting_user")).toMatchObject({ url: "https://auth.openai.com/codex/device", code: "ABCD-EFGH" })
  expect(states.find(s => s.phase === "awaiting_user")?.needsCode).toBeUndefined()
  expect(account).toMatchObject({ agent: "codex", method: "subscription", identity: { email: "new@x.io", accountId: "acct-new" } })
  expect(account.id).toMatch(/^codex-[0-9a-f]{8}$/)
  const [run] = await invocations()
  expect(run.args).toEqual(["-c", 'cli_auth_credentials_store="file"', "login", "--device-auth"])
  expect(run.leaked).toEqual([])
  const home = join(stateDirectory, "accounts", "homes", "codex", account.id)
  expect(JSON.parse(await readFile(join(home, "auth.json"), "utf8")).tokens.account_id).toBe("acct-new")
  expect((await lstat(join(home, "sessions"))).isSymbolicLink()).toBe(true)
  expect(await empty(pending)).toBe(true)
  expect(() => handle.submitCode("x")).toThrow()
})

test("codex as token: tokens move to the vault (expiry from the JWT), no home, temp dir removed", async () => {
  const { core, vault, stateDirectory, pending } = await setup()
  const account = await core.accounts.login({ agent: "codex", id: "ct", as: "token" }).done
  expect(account).toMatchObject({ id: "ct", method: "token", identity: { email: "new@x.io", accountId: "acct-new" } })
  const secret = JSON.parse((await vault.get("ct"))!)
  expect(Object.keys(secret).sort()).toEqual(["access_token", "account_id", "expires_at", "id_token", "refresh_token"])
  expect(secret.account_id).toBe("acct-new")
  expect(secret.expires_at).toBeGreaterThan(Date.now() / 1000)
  expect(await empty(join(stateDirectory, "accounts", "homes"))).toBe(true)
  expect(await empty(pending)).toBe(true)
})

test("grok and cursor: identity read from their credential files; promoted homes", async () => {
  const { core, stateDirectory, pending, invocations } = await setup()
  const grokHandle = core.accounts.login({ agent: "grok", id: "gk" })
  expect((await phase(grokHandle, "awaiting_user"))).toMatchObject({ url: "https://accounts.x.ai/device?user_code=WXYZ-1234", code: "WXYZ-1234" })
  expect(await grokHandle.done).toMatchObject({ method: "subscription", identity: { email: "new@x.io", org: "org-new", accountId: "acct-new" } })
  expect(JSON.parse(await readFile(join(stateDirectory, "accounts", "homes", "grok", "gk", "auth.json"), "utf8"))).toBeDefined()
  const cursorHandle = core.accounts.login({ agent: "cursor", id: "cu" })
  expect((await phase(cursorHandle, "awaiting_user")).url).toBe("https://cursor.com/loginDeepControl?challenge=abc&uuid=u1&mode=login")
  expect(await cursorHandle.done).toMatchObject({ method: "subscription", identity: { accountId: "acct-new" } })
  expect(JSON.parse(await readFile(join(stateDirectory, "accounts", "homes", "cursor", "cu", "xdg", "cursor", "auth.json"), "utf8")).accessToken).toBeDefined()
  const [grok, cursor] = await invocations()
  expect(grok.env.GROK_AUTH_PATH).toBe(join(grok.env.GROK_HOME, "auth.json"))
  expect(grok.env.HOME).toBe(grok.env.GROK_HOME)
  expect(cursor.env.NO_OPEN_BROWSER).toBe("1")
  expect(cursor.env.XDG_CONFIG_HOME.endsWith("/xdg")).toBe(true)
  expect(await empty(pending)).toBe(true)
})

test("duplicate identity (the system login) → account_exists naming it; temp dir removed, nothing promoted", async () => {
  const { core, roots, stateDirectory, pending } = await setup({ identity: { email: "me@x.io", accountId: "acct-system" } })
  await mkdir(roots.codexRoot, { recursive: true })
  await writeFile(join(roots.codexRoot, "auth.json"), JSON.stringify({ tokens: { account_id: "acct-system" } }))
  const handle = core.accounts.login({ agent: "codex", id: "dup" })
  await expect(handle.done).rejects.toMatchObject({ code: "account_exists", message: expect.stringContaining("codex:system") })
  expect(handle.state()).toMatchObject({ phase: "failed", errorCode: "account_exists" })
  expect(await empty(pending)).toBe(true)
  expect(await empty(join(stateDirectory, "accounts", "homes"))).toBe(true)
  expect(await core.accounts.get("dup")).toBeUndefined()
  // Same for a token login of that identity (it carries a refresh token).
  await expect(core.accounts.login({ agent: "codex", as: "token" }).done).rejects.toMatchObject({ code: "account_exists" })
  expect(await empty(pending)).toBe(true)
})

test("cancel kills the process group and removes the temp dir", async () => {
  const { core, pending, invocations } = await setup({ behavior: "hang" })
  const handle = core.accounts.login({ agent: "codex" })
  await phase(handle, "awaiting_user")
  const [run] = await invocations()
  expect(alive(run.pid)).toBe(true)
  handle.cancel()
  await expect(handle.done).rejects.toMatchObject({ code: "login_cancelled" })
  expect(handle.state().phase).toBe("cancelled")
  expect(await gone(run.pid)).toBe(true)
  expect(await empty(pending)).toBe(true)
})

test("timeout fails the login and cleans up", async () => {
  const { core, pending, invocations } = await setup({ behavior: "hang", timeoutMs: 400 })
  const handle = core.accounts.login({ agent: "grok" })
  await expect(handle.done).rejects.toMatchObject({ code: "login_timeout" })
  expect(handle.state()).toMatchObject({ phase: "failed", errorCode: "login_timeout" })
  const [run] = await invocations()
  expect(await gone(run.pid)).toBe(true)
  expect(await empty(pending)).toBe(true)
})

test("failures: non-zero exit, missing credential file, wrong code, wrong email — always cleaned", async () => {
  {
    const { core, pending } = await setup({ behavior: "fail" })
    const handle = core.accounts.login({ agent: "codex" })
    await expect(handle.done).rejects.toMatchObject({ code: "login_failed", message: expect.stringContaining("device code expired") })
    expect(await empty(pending)).toBe(true)
  }
  {
    const { core, pending } = await setup({ behavior: "nocred" })
    await expect(core.accounts.login({ agent: "cursor" }).done).rejects.toMatchObject({ code: "login_failed", message: expect.stringContaining("no credential file") })
    expect(await empty(pending)).toBe(true)
  }
  {
    const { core, pending } = await setup()
    const handle = core.accounts.login({ agent: "claude" })
    await phase(handle, "awaiting_user")
    handle.submitCode("BAD-CODE")
    await expect(handle.done).rejects.toMatchObject({ code: "login_failed" })
    expect(await empty(pending)).toBe(true)
  }
  {
    const { core, pending } = await setup()
    const handle = core.accounts.login({ agent: "claude", email: "other@x.io" })
    await phase(handle, "awaiting_user")
    handle.submitCode("GOOD-CODE")
    await expect(handle.done).rejects.toMatchObject({ code: "account_identity_mismatch" })
    expect(await empty(pending)).toBe(true)
  }
  {
    // Missing binary.
    const { core, pending } = await setup()
    const missing = createCore({ stateDirectory: join(pending, "..", "..", "..", "s2"), agents: [driver("codex")], limits: TEST_LIMITS, accounts: { vault: memoryVault(), login: { commands: { codex: "/nonexistent/codex" } } } })
    cores.push(missing)
    await expect(missing.accounts.login({ agent: "codex" }).done).rejects.toMatchObject({ code: "login_failed" })
  }
})

test("validation: unsupported agents and methods throw synchronously; a taken id fails before spawning", async () => {
  const { core, invocations } = await setup()
  expect(() => core.accounts.login({ agent: "opencode" })).toThrow(expect.objectContaining({ code: "unsupported_operation" }))
  expect(() => core.accounts.login({ agent: "claude", as: "token" })).toThrow(expect.objectContaining({ code: "unsupported_operation" }))
  expect(() => core.accounts.login({ agent: "nope" })).toThrow(expect.objectContaining({ code: "unknown_agent" }))
  expect(() => core.accounts.login({ agent: "codex", email: "a@b" })).toThrow(expect.objectContaining({ code: "invalid_input" }))
  expect(() => core.accounts.login({ agent: "codex", id: "../x" })).toThrow(expect.objectContaining({ code: "invalid_account_id" }))
  await core.accounts.add({ id: "taken", agent: "codex", method: "api_key", secret: "k" })
  await expect(core.accounts.login({ agent: "codex", id: "taken" }).done).rejects.toMatchObject({ code: "account_exists" })
  expect(await invocations()).toEqual([])
})

test("injectable runner receives the spawn plan; core close cancels running logins", async () => {
  const spawns: Parameters<LoginRunner>[0][] = []
  let killed = false
  let exit!: (code: number | null) => void
  const runner: LoginRunner = spawn => {
    spawns.push(spawn)
    return {
      onOutput: listener => { setTimeout(() => listener("visit https://auth.openai.com/codex/device code QRST-UVWX\n"), 5) },
      onExit: listener => { exit = listener },
      write: () => {},
      kill: () => { killed = true; exit(null) },
    }
  }
  const { core, pending } = await setup({ runner })
  const handle = core.accounts.login({ agent: "codex" })
  expect(await phase(handle, "awaiting_user")).toMatchObject({ code: "QRST-UVWX" })
  expect(spawns[0]).toMatchObject({ args: ["-c", 'cli_auth_credentials_store="file"', "login", "--device-auth"], pty: false })
  expect(spawns[0]!.env.CODEX_HOME).toBe(spawns[0]!.cwd)
  await core.close({ agents: "shutdown" })
  expect(killed).toBe(true)
  await expect(handle.done).rejects.toMatchObject({ code: "login_cancelled" })
  expect(await empty(pending)).toBe(true)
})
