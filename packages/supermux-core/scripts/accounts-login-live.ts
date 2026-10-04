/**
 * Guided login against the REAL agent CLI (A2).
 *
 *   bun scripts/accounts-login-live.ts <claude|codex|grok|cursor> [--as subscription|token] [--id ID] [--label L]
 *       [--email E] [--state DIR] [--cancel-at-url]
 *
 * Prints the sign-in URL (and device code), reads the pasted code from stdin when the CLI needs one
 * (Claude), and prints the resulting Account (id, method, identity — never a secret). Core state
 * (accounts.json, vault, account homes) goes to --state (default ~/.cache/accounts-live/login-state).
 * History roots are the real ones (~/.claude, ~/.codex), as in production: a promoted subscription
 * home links to them, and a login of the same identity as the system login is refused.
 * --cancel-at-url stops at "awaiting_user", cancels, and checks that the temp dir is gone and that
 * the real credential files and home listings did not change (other sessions on this host may
 * touch ~/.claude.json legitimately; a change there is reported, not asserted).
 */
import { readdirSync, statSync } from "node:fs"
import { homedir } from "node:os"
import { join } from "node:path"
import { createInterface } from "node:readline"
import { createCore } from "../src/index.js"
import type { AgentDriver, LoginState } from "../src/index.js"

const argv = process.argv.slice(2)
const agent = argv[0]
if (!agent || agent.startsWith("-")) { console.error("usage: accounts-login-live.ts <agent> [--as subscription|token] [--id ID] [--label L] [--email E] [--state DIR] [--timeout-min N] [--cancel-at-url]"); process.exit(2) }
const flag = (name: string) => { const i = argv.indexOf(name); return i > 0 ? argv[i + 1] : undefined }
const as = flag("--as") as "subscription" | "token" | undefined
const cancelAtUrl = argv.includes("--cancel-at-url")
const stateDirectory = flag("--state") ?? join(homedir(), ".cache", "accounts-live", "login-state")

const watched = [
  join(homedir(), ".claude", ".credentials.json"), join(homedir(), ".claude.json"), join(homedir(), ".codex", "auth.json"),
  join(homedir(), ".grok", "auth.json"), join(homedir(), ".config", "cursor", "auth.json"),
]
const listed = [join(homedir(), ".claude"), join(homedir(), ".codex"), process.cwd()]
function snapshot(): Record<string, string> {
  const out: Record<string, string> = {}
  for (const path of watched) { try { const s = statSync(path); out[path] = `${s.mtimeMs}:${s.size}` } catch { out[path] = "missing" } }
  for (const dir of listed) { try { out[`${dir}/`] = readdirSync(dir).sort().join(",") } catch { out[`${dir}/`] = "missing" } }
  return out
}

const stub: AgentDriver = { id: agent, async open() { throw new Error("this script runs logins only") } }
const timeoutMinutes = Number(flag("--timeout-min") ?? 10)
const core = createCore({ stateDirectory, agents: [stub], limits: { interruptTimeoutMs: 10_000, maxPending: 16, outstandingActivity: 256 },
  accounts: { login: { timeoutMs: timeoutMinutes * 60_000 } } })
const before = snapshot()
const handle = core.accounts.login({
  agent,
  ...(as ? { as } : {}),
  ...(flag("--id") ? { id: flag("--id")! } : {}),
  ...(flag("--label") ? { label: flag("--label")! } : {}),
  ...(flag("--email") ? { email: flag("--email")! } : {}),
})
const pending = join(stateDirectory, "accounts", "pending")
let prompted = false
handle.on((state: LoginState) => {
  const { phase, url, code, needsCode, error, errorCode } = state
  console.log(JSON.stringify({ phase, ...(url ? { url } : {}), ...(code ? { code } : {}), ...(needsCode ? { needsCode } : {}), ...(error ? { error, errorCode } : {}) }))
  if (phase !== "awaiting_user") return
  if (cancelAtUrl) { handle.cancel(); return }
  if (needsCode && !prompted) {
    prompted = true
    const rl = createInterface({ input: process.stdin, output: process.stdout })
    rl.question("Paste the code from the sign-in page: ", answer => { rl.close(); handle.submitCode(answer) })
  } else if (!needsCode) console.log("Open the URL, sign in (enter the code if shown); waiting...")
})

let exitCode = 0
try {
  const account = await handle.done
  console.log(JSON.stringify({ account: { id: account.id, agent: account.agent, method: account.method, label: account.label, identity: account.identity, isolated: account.isolated } }, null, 2))
} catch (error) {
  const e = error as { code?: string; message?: string }
  console.log(JSON.stringify({ failed: { code: e.code, message: e.message } }))
  exitCode = cancelAtUrl && e.code === "login_cancelled" ? 0 : 1
}
await core.close({ agents: "shutdown" })
if (cancelAtUrl) {
  const after = snapshot()
  let left: string[] = []
  try { left = readdirSync(pending) } catch { left = [] }
  const changed = Object.keys(before).filter(key => before[key] !== after[key])
  console.log(JSON.stringify({ tempDirsLeft: left.length, changedOutsideTemp: changed }))
  if (left.length) exitCode = 1
}
process.exit(exitCode)
