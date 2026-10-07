import { spawn } from "node:child_process"
import { accessSync, constants } from "node:fs"
import { access, mkdir, readFile, rm, chmod } from "node:fs/promises"
import { randomUUID } from "node:crypto"
import { delimiter, isAbsolute, join } from "node:path"
import { CoreError, asError } from "../errors.js"
import { readClaudeIdentity } from "./adapters/claude.js"
import { codexTokenFromAuth, readCodexIdentity } from "./adapters/codex.js"
import { readCursorIdentity } from "./adapters/cursor.js"
import { readGrokIdentity } from "./adapters/grok.js"
import type { Account, AccountIdentity, LoginHandle, LoginOptions, LoginProcess, LoginRunner, LoginSpawn, LoginState } from "./types.js"

export type LoginKind = "claude" | "codex" | "grok" | "cursor"

/** Credential env vars removed from every login process, so the CLI cannot pick up another login. */
export const LOGIN_STRIPPED_ENV = [
  "ANTHROPIC_API_KEY", "ANTHROPIC_AUTH_TOKEN", "CLAUDE_CODE_OAUTH_TOKEN", "OPENAI_API_KEY", "CODEX_API_KEY", "CURSOR_API_KEY", "XAI_API_KEY",
]

const OUTPUT_LIMIT = 64 * 1024
const KILL_GRACE_MS = 2000
/** Claude's prompt takes a paste and its Enter as separate inputs (observed on 2.1.289). */
const ENTER_DELAY_MS = 150

// Login CLI output parsing (ported from the broker's login/parse.ts). The CLIs colour their
// output and Claude prints its URL as an OSC 8 hyperlink; strip both before matching.
const URL_RE = /https?:\/\/[^\s'"\x00-\x1f\x7f]+/
const CODE_RE = /\b([A-Z0-9]{3,}-[A-Z0-9]{3,})\b/
const ANSI_RE = /\x1b\[[0-9;?]*[A-Za-z]/g
const OSC_RE = /\x1b\][^\x07\x1b]*(?:\x07|\x1b\\)/g

export function stripAnsi(text: string): string {
  return text.replace(OSC_RE, "").replace(ANSI_RE, "")
}

/** Device-code logins (codex, grok): a URL and an `XXXX-XXXX` code, once both have appeared. */
export function parseDeviceAuth(output: string): { url: string; code: string } | undefined {
  const clean = stripAnsi(output)
  const url = clean.match(URL_RE)?.[0]
  const code = clean.match(CODE_RE)?.[1]
  return url && code ? { url, code } : undefined
}

/** Browser logins (claude, cursor): the first URL. */
export function parseLoginUrl(output: string): string | undefined {
  return stripAnsi(output).match(URL_RE)?.[0]
}

/** What one agent's login looks like. */
type Plan = {
  spawn: LoginSpawn
  parse(output: string): { url: string; code?: string } | undefined
  needsCode: boolean
  /** Must exist after a successful login. */
  credential: string
  identity(): Promise<AccountIdentity | undefined>
}

function quote(arg: string): string {
  return `'${arg.replace(/'/g, `'\\''`)}'`
}

/** First of `names` found executable on PATH (absolute names are taken as is). */
export function findCommand(names: string[], env: Record<string, string | undefined> = process.env): string | undefined {
  for (const name of names) {
    if (isAbsolute(name)) return name
    for (const dir of (env.PATH ?? "").split(delimiter)) {
      if (!dir) continue
      try { accessSync(join(dir, name), constants.X_OK); return join(dir, name) } catch { /* next */ }
    }
  }
  return undefined
}

export function loginEnv(extra: Record<string, string>): Record<string, string> {
  const env: Record<string, string> = {}
  for (const [key, value] of Object.entries(process.env)) if (value !== undefined && !LOGIN_STRIPPED_ENV.includes(key)) env[key] = value
  return { ...env, ...extra }
}

export function loginPlan(kind: LoginKind, directory: string, command: string, email?: string): Plan {
  switch (kind) {
    case "claude": return {
      spawn: { command, args: ["auth", "login", "--claudeai", ...(email ? ["--email", email] : [])], env: loginEnv({ CLAUDE_CONFIG_DIR: directory }), cwd: directory, pty: true },
      parse: output => { const url = parseLoginUrl(output); return url ? { url } : undefined },
      needsCode: true,
      credential: join(directory, ".credentials.json"),
      identity: () => readClaudeIdentity(directory),
    }
    case "codex": return {
      spawn: { command, args: ["-c", 'cli_auth_credentials_store="file"', "login", "--device-auth"], env: loginEnv({ CODEX_HOME: directory }), cwd: directory, pty: false },
      parse: parseDeviceAuth,
      needsCode: false,
      credential: join(directory, "auth.json"),
      identity: () => readCodexIdentity(directory),
    }
    case "grok": return {
      spawn: { command, args: ["login", "--device-auth"], env: loginEnv({ GROK_AUTH_PATH: join(directory, "auth.json"), GROK_HOME: directory, HOME: directory }), cwd: directory, pty: false },
      parse: parseDeviceAuth,
      needsCode: false,
      credential: join(directory, "auth.json"),
      identity: () => readGrokIdentity(join(directory, "auth.json")),
    }
    case "cursor": return {
      spawn: { command, args: ["login"], env: loginEnv({ XDG_CONFIG_HOME: join(directory, "xdg"), NO_OPEN_BROWSER: "1" }), cwd: directory, pty: false },
      parse: output => { const url = parseLoginUrl(output); return url ? { url } : undefined },
      needsCode: false,
      credential: join(directory, "xdg", "cursor", "auth.json"),
      identity: () => readCursorIdentity(join(directory, "xdg")),
    }
  }
}

/**
 * The command line that gives `spawn` a pseudo-terminal through `script` (util-linux on Linux,
 * BSD on macOS; ported from the broker's spawn-command.ts). Windows has no `script`.
 */
export function ptyCommand(spawn: Pick<LoginSpawn, "command" | "args">, platform = process.platform): { command: string; args: string[] } {
  const shell = `stty cols 600; exec ${[spawn.command, ...spawn.args].map(quote).join(" ")}`
  if (platform === "win32") throw new CoreError("unsupported_operation", "Guided Claude login needs a pseudo-terminal (`script`), which Windows does not have")
  if (platform === "darwin") {
    // BSD script calls tcgetattr() on stdin, which fails on the socketpair Node uses: `cat |`
    // turns it into a pipe that stays writable for the pasted code; the trap kills `cat` when
    // script exits so the process group can end.
    return {
      command: "/bin/sh",
      args: [
        "-c",
        `cat | /bin/sh -c 'trap "/usr/bin/pkill -TERM -P \\"$PPID\\" -x cat 2>/dev/null || true" EXIT; /usr/bin/script -q /dev/null /bin/sh -c "$1"' supermux-login-script "$1"`,
        "supermux-login",
        shell,
      ],
    }
  }
  return { command: "script", args: ["-qec", shell, "/dev/null"] }
}

/** Default runner: its own process group (killed as a whole); PTY logins go through `script`. */
export const defaultLoginRunner: LoginRunner = input => {
  const { command, args } = input.pty ? ptyCommand(input) : input
  const child = spawn(command, args, { cwd: input.cwd, env: input.env, detached: process.platform !== "win32", stdio: ["pipe", "pipe", "pipe"] })
  const output: Array<(chunk: string) => void> = []
  const exits: Array<(code: number | null) => void> = []
  let exited: { code: number | null } | undefined
  const exit = (code: number | null) => {
    if (exited) return
    exited = { code }
    for (const listener of exits) listener(code)
  }
  child.stdout.setEncoding("utf8")
  child.stderr.setEncoding("utf8")
  child.stdout.on("data", (chunk: string) => { for (const listener of output) listener(chunk) })
  child.stderr.on("data", (chunk: string) => { for (const listener of output) listener(chunk) })
  child.stdin.on("error", () => {})
  child.on("error", error => {
    for (const listener of output) listener(`${command}: ${error.message}\n`)
    exit(null)
  })
  child.on("exit", code => exit(code))
  const signal = (name: NodeJS.Signals) => {
    try {
      if (child.pid && process.platform !== "win32") process.kill(-child.pid, name)
      else child.kill(name)
    } catch { /* already gone */ }
  }
  return {
    onOutput: listener => { output.push(listener) },
    onExit: listener => { if (exited) listener(exited.code); else exits.push(listener) },
    write: data => { try { child.stdin.write(data) } catch { /* closed */ } },
    kill: () => {
      if (exited) return
      signal("SIGTERM")
      const timer = setTimeout(() => { if (!exited) signal("SIGKILL") }, KILL_GRACE_MS)
      timer.unref?.()
    },
  }
}

export type LoginDeps = {
  kind: LoginKind
  options: LoginOptions
  /** `<stateDirectory>/accounts/pending`. */
  pendingDirectory: string
  command: string
  runner: LoginRunner
  timeoutMs: number
  /** Fails early (e.g. account_exists for a taken id) before any process starts. */
  precheck(): Promise<void>
  /** Promotes a verified login: rename into a home (subscription) or move tokens to the vault (token). */
  promote(directory: string, identity: AccountIdentity, tokens?: string): Promise<Account>
}

/**
 * Runs an agent's own login in a throwaway directory (0700, credential env stripped, the agent's
 * isolation knob pointed at it). Success = exit 0, the credential file exists and the identity
 * is readable. The directory is always removed unless it became the account home.
 */
export function startLogin(deps: LoginDeps): LoginHandle {
  let state: LoginState = { phase: "starting" }
  const listeners = new Set<(state: LoginState) => void>()
  let proc: LoginProcess | undefined
  let directory: string | undefined
  let finished = false
  let timer: ReturnType<typeof setTimeout> | undefined
  let exited: Promise<void> = Promise.resolve()
  let output = ""
  let resolveDone!: (account: Account) => void
  let rejectDone!: (error: Error) => void
  const done = new Promise<Account>((resolve, reject) => { resolveDone = resolve; rejectDone = reject })
  done.catch(() => {})

  const set = (patch: Partial<LoginState>) => {
    state = { ...state, ...patch }
    const snapshot = { ...state }
    for (const listener of listeners) { try { listener(snapshot) } catch { /* listeners cannot break the login */ } }
  }
  const cleanup = async () => {
    if (timer) clearTimeout(timer)
    proc?.kill()
    await Promise.race([exited, new Promise(resolve => { const t = setTimeout(resolve, KILL_GRACE_MS + 1000); t.unref?.() })])
    if (directory) await rm(directory, { recursive: true, force: true }).catch(() => {})
  }
  const fail = (error: CoreError, phase: "failed" | "cancelled" = "failed") => {
    if (finished) return
    finished = true
    set({ phase, error: error.message, errorCode: error.code })
    void cleanup().finally(() => rejectDone(error))
  }

  const verify = async (dir: string, plan: Plan) => {
    set({ phase: "verifying" })
    try {
      await access(plan.credential)
    } catch { throw new CoreError("login_failed", "The login finished but wrote no credential file") }
    const identity = await plan.identity()
    if (!identity) throw new CoreError("login_failed", "The login finished but its identity cannot be read")
    const email = deps.options.email
    if (email && identity.email && identity.email.toLowerCase() !== email.toLowerCase()) {
      throw new CoreError("account_identity_mismatch", `Signed in as a different account than ${email}`)
    }
    let tokens: string | undefined
    if (deps.options.as === "token") {
      let auth: unknown
      try { auth = JSON.parse(await readFile(join(dir, "auth.json"), "utf8")) } catch { auth = undefined }
      const token = codexTokenFromAuth(auth)
      if (!token) throw new CoreError("login_failed", "The Codex login wrote no ChatGPT tokens")
      tokens = JSON.stringify(token)
    }
    return deps.promote(dir, identity, tokens)
  }

  const run = async () => {
    await deps.precheck()
    await mkdir(deps.pendingDirectory, { recursive: true, mode: 0o700 })
    await chmod(deps.pendingDirectory, 0o700)
    const dir = join(deps.pendingDirectory, randomUUID())
    await mkdir(dir, { mode: 0o700 })
    directory = dir
    if (finished) { await rm(dir, { recursive: true, force: true }); return }
    const plan = loginPlan(deps.kind, dir, deps.command, deps.options.email)
    if (deps.kind === "cursor") await mkdir(join(dir, "xdg"), { mode: 0o700 })
    const child = deps.runner(plan.spawn)
    proc = child
    exited = new Promise(resolve => child.onExit(() => resolve()))
    timer = setTimeout(() => fail(new CoreError("login_timeout", `Login did not finish within ${Math.round(deps.timeoutMs / 1000)} s`)), deps.timeoutMs)
    timer.unref?.()
    child.onOutput(chunk => {
      if (finished) return
      output = (output + chunk).slice(-OUTPUT_LIMIT)
      if (state.phase !== "starting") return
      const parsed = plan.parse(output)
      if (parsed) set({ phase: "awaiting_user", url: parsed.url, ...(parsed.code ? { code: parsed.code } : {}), ...(plan.needsCode ? { needsCode: true } : {}) })
    })
    child.onExit(code => {
      if (finished) return
      if (code !== 0) {
        const tail = stripAnsi(output).replace(/\s+/g, " ").trim().slice(-300)
        fail(new CoreError("login_failed", `Login exited with ${code === null ? "an error" : `code ${code}`}${tail ? `: ${tail}` : ""}`))
        return
      }
      if (timer) clearTimeout(timer)
      verify(dir, plan).then(account => {
        if (finished) return
        finished = true
        // A subscription login now lives in the account home; a token login was copied to the vault.
        const keep = deps.options.as !== "token"
        void (keep ? Promise.resolve() : rm(dir, { recursive: true, force: true }).catch(() => {})).then(() => {
          set({ phase: "done" })
          resolveDone(account)
        })
      }, error => fail(error instanceof CoreError ? error : new CoreError("login_failed", asError(error).message)))
    })
  }
  void run().catch(error => fail(error instanceof CoreError ? error : new CoreError("login_failed", asError(error).message)))

  return {
    state: () => ({ ...state }),
    on(listener) { listeners.add(listener); return () => { listeners.delete(listener) } },
    submitCode(text) {
      if (typeof text !== "string" || !text.trim()) throw new CoreError("invalid_input", "code must be a nonempty string")
      if (!state.needsCode || state.phase !== "awaiting_user" || !proc) throw new CoreError("invalid_input", "This login is not waiting for a code")
      const child = proc
      child.write(text.trim())
      const enter = setTimeout(() => { if (!finished) child.write("\r") }, ENTER_DELAY_MS)
      enter.unref?.()
    },
    /** No effect once the login is verifying (the process has exited and the account is being saved). */
    cancel() { if (state.phase !== "verifying") fail(new CoreError("login_cancelled", "Login cancelled"), "cancelled") },
    done,
  }
}
