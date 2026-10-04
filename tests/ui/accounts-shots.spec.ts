/**
 * Visual harness + journey for accounts in the app UI (slice A3b). Stubs the broker's accounts
 * routes (GET/POST/DELETE /accounts, /accounts/login…, /settings/accounts, POST
 * /sessions/:id/account) per context with `page.route`, injects `account_login_state`,
 * `session_state` and `message_append` frames, and screenshots each state at phone (390) and
 * desktop (900) widths in dark and light:
 *
 *   01 Settings → Accounts, system logins only     08 login: verifying
 *   02 … system + two added (usage meters, isolated) 09 login: done
 *   03 add-account chooser (Claude)                 10 login: failed (account_exists)
 *   04 paste-a-token form                           11 auto-switch on
 *   05 login: starting                              12 new chat: account picker open
 *   06 login: awaiting_user (Codex URL + code)      13 session pill + switch menu
 *   07 login: Claude paste-the-code                 14 switch refused (busy) · 15 "Switched to…" notice
 *
 * On the "after" build it also asserts the requests that reach the broker: the login start, the
 * pasted code, cancel, the auto-switch PUT, SpawnRequest.account and the session switch.
 *
 * LABEL=before shoots only today's Settings → Agents and the launcher.
 *
 * Run: scripts/test-broker.sh bun tests/ui/accounts-shots.spec.ts
 * Out: $ACCOUNTS_SHOTS_DIR (default /tmp/claude-1000/accounts-shots/<ACCOUNTS_SHOTS_LABEL|after>)
 */
import { mkdirSync } from "node:fs"
import type { Browser, BrowserContext, Page, Route } from "playwright"
import { launchBrowser, uiFixture, injectServerFrame } from "./fixture-env"
import { byTag, openSeededSession, tap, typeInto, waitReady } from "./compose-dom"

const LABEL = process.env.ACCOUNTS_SHOTS_LABEL || "after"
const OUT = process.env.ACCOUNTS_SHOTS_DIR || `/tmp/claude-1000/accounts-shots/${LABEL}`
const AFTER = LABEL !== "before"

type Variant = { width: number; height: number; theme: "dark" | "light" }
const VARIANTS: Variant[] = [
  { width: 390, height: 844, theme: "dark" },
  { width: 390, height: 844, theme: "light" },
  { width: 900, height: 900, theme: "dark" },
  { width: 900, height: 900, theme: "light" },
]

const settle = (page: Page, ms = 700) => page.waitForTimeout(ms)

async function exists(page: Page, id: string): Promise<boolean> {
  return (await page.locator(`[id="${id}"]`).count()) > 0
}

async function waitTag(page: Page, id: string, timeout = 20_000): Promise<void> {
  await byTag(page, id).waitFor({ state: "attached", timeout })
}

/**
 * Tap [tag] until [expect] is attached. The a11y mirror rebuilds on a debounce (and a closing
 * sheet briefly keeps stale nodes), so a single dispatched click occasionally lands on nothing.
 */
async function tapUntil(page: Page, tag: string, expect: string, tries = 4): Promise<void> {
  for (let i = 0; i < tries; i++) {
    await tap(byTag(page, tag))
    const ok = await byTag(page, expect).waitFor({ state: "attached", timeout: 4_000 }).then(() => true, () => false)
    if (ok) return
  }
  throw new Error(`tapping ${tag} never showed ${expect}`)
}

async function shot(page: Page, v: Variant, name: string, tag?: string, pad = 16): Promise<void> {
  const file = `${OUT}/${v.width}-${v.theme}-${name}.png`
  const box = tag ? await page.locator(`[id="${tag}"]`).first().boundingBox().catch(() => null) : null
  if (box && box.width > 0 && box.height > 0) {
    const vp = page.viewportSize()!
    const x = Math.max(0, box.x - pad)
    const y = Math.max(0, box.y - pad)
    await page.screenshot({
      path: file,
      clip: { x, y, width: Math.min(vp.width - x, box.width + pad * 2), height: Math.min(vp.height - y, box.height + pad * 2) },
    })
  } else {
    await page.screenshot({ path: file })
  }
  console.log(`shot ${file}`)
}

// ── fixtures ───────────────────────────────────────────────────────────────────────────────────

const inHours = (h: number) => new Date(Date.now() + h * 3_600_000).toISOString()

function systemAccounts(): Record<string, unknown>[] {
  const sys = (agent: string, email?: string) => ({
    id: `${agent}:system`, agent, method: "system", label: "System login",
    ...(email ? { identity: { email } } : {}),
    isolated: false, system: true, createdAt: "1970-01-01T00:00:00.000Z", usage: [],
  })
  return [
    sys("claude", "ahmet@ustalabs.com"),
    sys("codex", "ahmet@ustalabs.com"),
    sys("cursor"),
    sys("grok"),
    sys("opencode"),
  ]
}

function fullAccounts(): Record<string, unknown>[] {
  const [claude, codex, ...rest] = systemAccounts()
  return [
    { ...claude, usage: [{ name: "five_hour", usedPercent: 41, resetsAt: inHours(2.2) }] },
    {
      id: "claude-work", agent: "claude", method: "subscription", label: "Work (Max)", customLabel: "Work (Max)",
      identity: { email: "ahmet@acme.dev", org: "Acme" }, isolated: false, system: false,
      createdAt: "2026-10-01T10:00:00.000Z",
      usage: [
        { name: "five_hour", usedPercent: 62, resetsAt: inHours(1.4) },
        { name: "seven_day", usedPercent: 18, resetsAt: inHours(80) },
      ],
    },
    {
      id: "claude-ci-token", agent: "claude", method: "token", label: "CI token", customLabel: "CI token",
      isolated: true, system: false, createdAt: "2026-10-02T10:00:00.000Z",
      usage: [{ name: "five_hour", usedPercent: 94, resetsAt: inHours(0.3) }],
    },
    codex,
    {
      id: "codex-key", agent: "codex", method: "api_key", label: "OpenAI key", customLabel: "OpenAI key",
      isolated: false, system: false, createdAt: "2026-10-03T10:00:00.000Z", usage: [],
    },
    ...rest,
  ]
}

/** Per-context broker stub state + the requests the app sent. */
type Stub = {
  accounts: Record<string, unknown>[]
  autoSwitch: boolean
  loginAgent: string
  switchBusy: boolean
  calls: Array<{ method: string; path: string; body: string }>
}

async function stubAccounts(page: Page, stub: Stub): Promise<void> {
  const json = (route: Route, body: unknown, status = 200) =>
    route.fulfill({ status, contentType: "application/json", body: JSON.stringify(body) })
  await page.route(
    (url) => /^\/(accounts(\/.*)?|settings\/accounts|sessions\/[^/]+\/account)$/.test(url.pathname),
    async (route) => {
      const req = route.request()
      // `/settings/accounts` is ALSO the app's own page: a document navigation gets the SPA shell.
      if (req.isNavigationRequest()) return route.continue()
      const url = new URL(req.url())
      const path = url.pathname
      const method = req.method()
      stub.calls.push({ method, path, body: req.postData() ?? "" })
      if (path === "/accounts" && method === "GET") {
        const agent = url.searchParams.get("agent")
        return json(route, { accounts: agent ? stub.accounts.filter((a) => a.agent === agent) : stub.accounts })
      }
      if (path === "/accounts" && method === "POST") {
        const body = JSON.parse(req.postData() || "{}")
        const created = {
          id: `${body.agent}-new`, agent: body.agent, method: body.method, label: body.label || "Token",
          isolated: false, system: false, createdAt: new Date().toISOString(), usage: [],
        }
        return json(route, created)
      }
      if (path === "/accounts/login" && method === "POST") {
        const body = JSON.parse(req.postData() || "{}")
        stub.loginAgent = body.agent
        return json(route, { loginId: "login-1", agent: body.agent, phase: "starting" })
      }
      if (path.startsWith("/accounts/login/") && method === "GET") return json(route, { error: "no such login" }, 404)
      if (path.startsWith("/accounts/login/")) return json(route, { ok: true })
      if (path.startsWith("/accounts/") && method === "DELETE") return json(route, { ok: true })
      if (path === "/settings/accounts" && method === "GET") return json(route, { autoSwitch: stub.autoSwitch })
      if (path === "/settings/accounts" && method === "PUT") {
        stub.autoSwitch = JSON.parse(req.postData() || "{}").autoSwitch === true
        return json(route, { autoSwitch: stub.autoSwitch })
      }
      if (/^\/sessions\/[^/]+\/account$/.test(path)) {
        if (stub.switchBusy) {
          return json(route, { error: "Session is busy: switch after the current turn", code: "session_busy" }, 409)
        }
        const body = JSON.parse(req.postData() || "{}")
        const acc = stub.accounts.find((a) => a.id === body.account)
        return json(route, { ok: true, session: path.split("/")[2], account: body.account, accountLabel: acc?.label ?? body.account })
      }
      return json(route, { error: "not found" }, 404)
    },
  )
}

function lastCall(stub: Stub, method: string, re: RegExp) {
  return [...stub.calls].reverse().find((c) => c.method === method && re.test(c.path))
}

function expect(cond: unknown, msg: string): void {
  if (!cond) throw new Error(msg)
}

// ── variants ──────────────────────────────────────────────────────────────────────────────────

async function newPage(browser: Browser, storage: Awaited<ReturnType<BrowserContext["storageState"]>>, v: Variant): Promise<{ context: BrowserContext; page: Page }> {
  const context = await browser.newContext({ viewport: { width: v.width, height: v.height }, colorScheme: v.theme, storageState: storage, deviceScaleFactor: 2 })
  await context.addInitScript((mode) => {
    try {
      localStorage.setItem("supermux:appearance:mode", mode)
      // The web-push prompt overlays the top of every screen; these shots are about accounts.
      localStorage.setItem("cmux:push:banner-dismissed", "1")
    } catch {}
  }, v.theme === "dark" ? "DARK" : "LIGHT")
  const page = await context.newPage()
  page.on("pageerror", (error) => console.error(`[pageerror] ${error.message}`))
  return { context, page }
}

async function beforeVariant(browser: Browser, storage: Awaited<ReturnType<BrowserContext["storageState"]>>, v: Variant, baseUrl: string): Promise<void> {
  const { context, page } = await newPage(browser, storage, v)
  try {
    await page.goto(`${baseUrl}/settings/agents`, { waitUntil: "domcontentloaded" })
    await waitTag(page, "settings_hub", 60_000)
    if (v.width < 600) {
      // The compact hub opens on its index for "agents"; push the section.
      await shot(page, v, "00-settings-index")
      await tap(byTag(page, "settings_row_agents"))
    }
    await waitTag(page, "agent_settings_screen", 30_000)
    await settle(page, 2500)
    await shot(page, v, "01-settings-agents")
    await page.goto(`${baseUrl}/new`, { waitUntil: "domcontentloaded" })
    await waitTag(page, "launcher_agent_pill", 60_000)
    await settle(page, 2500)
    await shot(page, v, "02-launcher")
  } finally {
    await context.close()
  }
}

async function loginFrame(page: Page, stub: Stub, phase: string, extra: Record<string, unknown> = {}): Promise<void> {
  await injectServerFrame(page, { type: "account_login_state", loginId: "login-1", agent: stub.loginAgent, phase, ...extra })
  await settle(page, 900)
}

async function openAccountsSettings(page: Page, baseUrl: string): Promise<void> {
  await page.goto(`${baseUrl}/settings/accounts`, { waitUntil: "domcontentloaded" })
  await waitTag(page, "accounts_settings_screen", 60_000)
  await settle(page, 1500)
}

async function afterVariant(browser: Browser, storage: Awaited<ReturnType<BrowserContext["storageState"]>>, v: Variant, sessionId: string, baseUrl: string): Promise<void> {
  const { context, page } = await newPage(browser, storage, v)
  const stub: Stub = { accounts: systemAccounts(), autoSwitch: false, loginAgent: "claude", switchBusy: false, calls: [] }
  await stubAccounts(page, stub)
  try {
    // 01 — system logins only.
    await openAccountsSettings(page, baseUrl)
    await waitTag(page, "account-row:claude:system")
    await shot(page, v, "01-accounts-system-only")

    // 02 — two added Claude accounts + a Codex key; the screen refetches on accounts_changed.
    stub.accounts = fullAccounts()
    await injectServerFrame(page, { type: "accounts_changed" })
    await waitTag(page, "account-row:claude-work")
    await settle(page, 900)
    await shot(page, v, "02-accounts-full")
    await page.mouse.move(v.width / 2, v.height / 2)
    await page.mouse.wheel(0, 1200)
    await settle(page, 700)
    await shot(page, v, "02b-accounts-full-scrolled")
    await page.mouse.wheel(0, -3000)
    await settle(page, 500)

    // 03 — the add chooser for Claude.
    await tapUntil(page, "account-add:claude", "account-add-sheet")
    await settle(page)
    await shot(page, v, "03-add-chooser")

    // 04 — paste a token.
    await tapUntil(page, "account-add-option:token", "account-token-field")
    await settle(page)
    await shot(page, v, "04-add-token")
    await tapUntil(page, "account-add-back", "account-add-option:login")

    // 05..10 — guided login (Claude: starting → awaiting_user/needsCode → verifying → done).
    await tapUntil(page, "account-add-option:login", "account-login-sheet")
    const start = lastCall(stub, "POST", /^\/accounts\/login$/)
    expect(start && JSON.parse(start.body).agent === "claude", `login start body ${start?.body}`)
    await settle(page)
    await shot(page, v, "05-login-starting")

    await loginFrame(page, stub, "awaiting_user", { url: "https://claude.ai/oauth/authorize?code=true&client_id=9d1c250a", needsCode: true })
    await waitTag(page, "account-login-code")
    await shot(page, v, "07-login-claude-paste")
    await typeInto(page, "account-login-code", "Xy7Qp-2mLk#state-91f2")
    await settle(page, 300)
    await tap(byTag(page, "account-login-submit"))
    await settle(page, 900)
    const code = lastCall(stub, "POST", /^\/accounts\/login\/login-1\/code$/)
    expect(code && JSON.parse(code.body).code === "Xy7Qp-2mLk#state-91f2", `code body ${code?.body}`)

    await loginFrame(page, stub, "verifying")
    await shot(page, v, "08-login-verifying")
    const added = fullAccounts()[1]
    await injectServerFrame(page, { type: "account_login_state", loginId: "login-1", agent: stub.loginAgent, phase: "done", account: { ...added, id: "claude-new", label: "ahmet@acme.dev", customLabel: undefined } })
    await waitTag(page, "account-login-done")
    await settle(page, 350)
    await shot(page, v, "09-login-done")
    await settle(page, 3000) // the sheet closes itself ~2 s after "done"
    // Compose-for-Web stops syncing its a11y mirror once a modal layer closes (it empties under a
    // bottom sheet, goes stale under a dialog), so the next step reloads the page rather than
    // tapping into a void. The app itself is fine — see the screenshots.
    await openAccountsSettings(page, baseUrl)

    // 06 — Codex device login (URL + code), then a failure, then cancel.
    await tapUntil(page, "account-add:codex", "account-add-option:login")
    await tapUntil(page, "account-add-option:login", "account-login-sheet")
    await loginFrame(page, stub, "awaiting_user", { url: "https://auth.openai.com/codex/device", code: "GXQM-7PRT" })
    await waitTag(page, "account-login-open")
    await shot(page, v, "06-login-codex-device")
    await loginFrame(page, stub, "failed", { errorCode: "account_exists", error: "Account codex:system is already logged in as this identity; a second copy of a rotating login would log one of them out" })
    await waitTag(page, "account-login-retry")
    await shot(page, v, "10-login-failed-exists")
    // Try again starts a fresh login; Cancel on a live one reaches the broker.
    const startsBefore = stub.calls.filter((c) => c.method === "POST" && c.path === "/accounts/login").length
    await tap(byTag(page, "account-login-retry"))
    await waitTag(page, "account-login-starting")
    expect(stub.calls.filter((c) => c.method === "POST" && c.path === "/accounts/login").length === startsBefore + 1, "retry did not start a new login")
    await tap(byTag(page, "account-login-cancel"))
    await settle(page, 1500)
    expect(lastCall(stub, "POST", /^\/accounts\/login\/login-1\/cancel$/), "cancel never reached the broker")
    await shot(page, v, "10b-after-cancel")
    await openAccountsSettings(page, baseUrl)

    // 11 — auto-switch.
    await tap(byTag(page, "accounts-autoswitch"))
    await settle(page, 900)
    const put = lastCall(stub, "PUT", /^\/settings\/accounts$/)
    expect(put && JSON.parse(put.body).autoSwitch === true, `autoswitch body ${put?.body}`)
    await shot(page, v, "11-autoswitch-on", "accounts-autoswitch-card")

    // 12 — new chat picker (claude has three accounts).
    // A compact window carries the account in the AGENT menu; wider ones get their own pill.
    const compactLauncher = v.width < 600
    const pickerTag = compactLauncher ? "launcher_agent_pill" : "account-picker"
    await page.goto(`${baseUrl}/new`, { waitUntil: "domcontentloaded" })
    await waitTag(page, pickerTag, 60_000)
    await settle(page, 1500)
    await shot(page, v, "12a-launcher-picker")
    await tapUntil(page, pickerTag, "account-picker-item:claude-work")
    await settle(page)
    await shot(page, v, "12-launcher-picker-open")
    await tap(byTag(page, "account-picker-item:claude-work"))
    await settle(page)
    await shot(page, v, "12b-launcher-picked")

    // 13–15 — the session pill, its switch menu, a busy refusal and the chat notice.
    await page.goto(baseUrl, { waitUntil: "domcontentloaded" })
    await openSeededSession(page, sessionId)
    await injectServerFrame(page, { type: "session_state", session: sessionId, account: "claude-work", accountLabel: "Work (Max)" })
    await waitTag(page, "session-account-pill")
    await settle(page)
    await shot(page, v, "13a-session-pill")
    await tapUntil(page, "session-account-pill", "session-account-item:claude:system")
    await settle(page)
    await shot(page, v, "13-session-switch-menu")
    stub.switchBusy = true
    await tap(byTag(page, "session-account-item:claude-ci-token"))
    await waitTag(page, "session-account-error")
    await settle(page)
    await shot(page, v, "14-session-switch-busy")
    const sw = lastCall(stub, "POST", /^\/sessions\/[^/]+\/account$/)
    expect(sw && JSON.parse(sw.body).account === "claude-ci-token", `switch body ${sw?.body}`)
    // Close the menu with a tap outside it (the transcript's lower half).
    await page.mouse.click(v.width / 2, v.height * 0.7)
    await settle(page, 600)
    await injectServerFrame(page, {
      type: "message_append", session: sessionId,
      entry: { id: `notice-${v.width}-${v.theme}`, ts: new Date().toISOString(), direction: "outbound", op: "reply", text: "Switched to CI token — usage limit reached" },
    })
    await injectServerFrame(page, { type: "session_state", session: sessionId, account: "claude-ci-token", accountLabel: "CI token" })
    await settle(page, 1200)
    await shot(page, v, "15-switched-notice")
  } catch (error) {
    await page.screenshot({ path: `${OUT}/${v.width}-${v.theme}-FAILED.png` }).catch(() => {})
    console.error(`[${v.width}-${v.theme}] calls: ${JSON.stringify(stub.calls.map((c) => `${c.method} ${c.path}`))}`)
    const ids = await page.locator('[id^="account"]').evaluateAll((els) => els.map((e) => e.id)).catch(() => [])
    console.error(`[${v.width}-${v.theme}] mirror ids: ${JSON.stringify(ids)}`)
    throw error
  } finally {
    await context.close()
  }
}

export async function main(): Promise<void> {
  if (process.env.MUX_RUN_UI_SMOKE !== "1") {
    console.log("skipping accounts shots; run through scripts/test-broker.sh")
    return
  }
  mkdirSync(OUT, { recursive: true })
  const fixture = uiFixture()
  const browser = await launchBrowser()
  try {
    const pairing = await browser.newContext()
    const pairPage = await pairing.newPage()
    await pairPage.goto(`${fixture.baseUrl}/pair?t=${encodeURIComponent(fixture.token)}`, { waitUntil: "domcontentloaded" })
    await waitReady(pairPage)
    const storage = await pairing.storageState()
    await pairing.close()

    const only = process.env.ACCOUNTS_SHOTS_ONLY // e.g. "390-dark"
    for (const v of VARIANTS) {
      if (only && `${v.width}-${v.theme}` !== only) continue
      if (AFTER) await afterVariant(browser, storage, v, fixture.sessionId, fixture.baseUrl)
      else await beforeVariant(browser, storage, v, fixture.baseUrl)
    }
    console.log(`ACCOUNTS SHOTS PASS → ${OUT}`)
  } finally {
    await browser.close()
  }
}

if (import.meta.main) {
  main().catch((error) => {
    console.error("ACCOUNTS SHOTS FAILED:", error instanceof Error ? error.stack ?? error.message : String(error))
    process.exit(1)
  })
}
