// Fake login CLI for accounts-login tests: `login-cli.mjs <kind> <behavior> <log> <identity-json> -- <cli args>`.
// Prints what the real CLI prints (URL, device code, ANSI noise), writes the credential file the
// real CLI writes into the isolated location, and logs its argv/env/pid to <log>.
import { appendFileSync, mkdirSync, writeFileSync } from "node:fs"
import { dirname, join } from "node:path"
import { createInterface } from "node:readline"

const [kind, behavior, log, identityJson, sep, ...args] = process.argv.slice(2)
if (sep !== "--") throw new Error("usage")
const identity = JSON.parse(identityJson)
const stripped = ["ANTHROPIC_API_KEY", "ANTHROPIC_AUTH_TOKEN", "CLAUDE_CODE_OAUTH_TOKEN", "OPENAI_API_KEY", "CODEX_API_KEY", "CURSOR_API_KEY", "XAI_API_KEY"]
appendFileSync(log, JSON.stringify({
  kind, pid: process.pid, args, tty: !!process.stdin.isTTY,
  leaked: stripped.filter(key => process.env[key] !== undefined),
  env: Object.fromEntries(["CLAUDE_CONFIG_DIR", "CODEX_HOME", "GROK_AUTH_PATH", "GROK_HOME", "HOME", "XDG_CONFIG_HOME", "NO_OPEN_BROWSER"].map(k => [k, process.env[k]])),
}) + "\n")

const b64 = value => Buffer.from(JSON.stringify(value)).toString("base64url")
const jwt = payload => `${b64({ alg: "none" })}.${b64(payload)}.sig`
const exp = Math.floor(Date.now() / 1000) + 3600
const write = (path, value) => { mkdirSync(dirname(path), { recursive: true }); writeFileSync(path, JSON.stringify(value)) }

function credentials() {
  if (behavior === "nocred") return
  if (kind === "claude") {
    write(join(process.env.CLAUDE_CONFIG_DIR, ".credentials.json"), { claudeAiOauth: { accessToken: "fake-access", refreshToken: "fake-refresh" } })
    write(join(process.env.CLAUDE_CONFIG_DIR, ".claude.json"), { oauthAccount: { emailAddress: identity.email, organizationUuid: identity.org, accountUuid: identity.accountId } })
    // Login can create empty dirs that are shared entries in the account home layout.
    mkdirSync(join(process.env.CLAUDE_CONFIG_DIR, "projects"), { recursive: true })
    writeFileSync(join(process.env.CLAUDE_CONFIG_DIR, "settings.json"), JSON.stringify({ login: "default" }))
  } else if (kind === "codex") {
    write(join(process.env.CODEX_HOME, "auth.json"), {
      OPENAI_API_KEY: null,
      tokens: { access_token: jwt({ exp }), refresh_token: "fake-refresh", id_token: jwt({ email: identity.email }), account_id: identity.accountId },
      last_refresh: new Date().toISOString(),
    })
  } else if (kind === "grok") {
    write(process.env.GROK_AUTH_PATH, { "https://auth.x.ai::client": { key: jwt({ exp }), email: identity.email, user_id: identity.accountId, team_id: identity.org, refresh_token: "fake-refresh" } })
  } else if (kind === "cursor") {
    write(join(process.env.XDG_CONFIG_HOME, "cursor", "auth.json"), { accessToken: jwt({ sub: identity.accountId, exp }), refreshToken: jwt({ sub: identity.accountId }) })
  }
}

function finish() {
  if (behavior === "fail") { console.error("Error: device code expired"); process.exit(1) }
  credentials()
  console.log("Successfully logged in")
  process.exit(0)
}

if (kind === "claude") {
  // OSC 8 hyperlink + colours, as claude 2.1.x prints it.
  const url = "https://claude.ai/oauth/authorize?code=true&client_id=fake&state=s1"
  process.stdout.write(`\x1b[1mBrowser didn't open? Use the url below to sign in:\x1b[0m\n\n\x1b]8;;${url}\x07${url}\x1b]8;;\x07\n\n`)
  process.stdout.write("Paste code here if prompted > ")
  const rl = createInterface({ input: process.stdin })
  let typed = ""
  rl.on("line", line => {
    typed += line
    if (!typed.trim()) return
    appendFileSync(log, JSON.stringify({ code: typed.trim() }) + "\n")
    if (typed.trim() === "GOOD-CODE") finish()
    else { console.error("OAuth error: invalid code"); process.exit(1) }
  })
} else if (kind === "cursor") {
  process.stdout.write("\x1b[2K\x1b[1GOpen this URL to log in: \x1b[36mhttps://cursor.com/loginDeepControl?challenge=abc&uuid=u1&mode=login\x1b[0m\n")
  if (behavior !== "hang") setTimeout(finish, 100)
} else {
  const url = kind === "codex" ? "https://auth.openai.com/codex/device" : "https://accounts.x.ai/device?user_code=WXYZ-1234"
  const code = kind === "codex" ? "ABCD-EFGH" : "WXYZ-1234"
  process.stdout.write(`Follow these steps to sign in:\n1. Open this link\n   \x1b[94m${url}\x1b[0m\n2. Enter this one-time code \x1b[94m${code}\x1b[0m\n`)
  if (behavior !== "hang") setTimeout(finish, 100)
}
if (behavior === "hang") setInterval(() => {}, 1000)
