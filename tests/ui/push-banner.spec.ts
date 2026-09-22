/**
 * UI smoke for the notifications banner + subscribe flow.
 *
 * Uses Playwright's `grantPermissions(["notifications"])` to bypass the OS
 * permission dialog. Verifies the banner appears, tapping Enable hits the
 * broker `/push/subscribe` endpoint, and the row lands in sqlite.
 *
 * Run: scripts/test-broker.sh bun tests/ui/push-banner.spec.ts
 */

import { chromium, type BrowserContext } from "playwright"
import { mkdtempSync, rmSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { Database } from "bun:sqlite"
import { browserLaunchOptions, uiFixture } from "./fixture-env"
import { waitReady } from "./compose-dom"

declare const window: object
declare const Notification: { permission: string } | undefined
declare const navigator: {
  serviceWorker?: {
    ready: Promise<{ pushManager?: unknown }>
  }
}

export async function main(): Promise<void> {
  if (process.env.MUX_RUN_UI_SMOKE !== "1") {
    console.log("skipping UI smoke; run through scripts/test-broker.sh")
    return
  }

  const fixture = uiFixture()
  const stateDir = process.env.MUX_STATE_DIR
  if (!stateDir) throw new Error("MUX_STATE_DIR missing from test-broker fixture")

  // Chrome refuses Push API in incognito (https://crbug.com/41124656). Playwright's
  // default `chromium.launch()` + `newContext()` runs in incognito-equivalent mode,
  // so we use `launchPersistentContext` with a temp profile dir to get the full
  // Push API surface.
  const profileDir = mkdtempSync(join(tmpdir(), "cmux-push-test-profile-"))
  let ctx: BrowserContext | null = null
  try {
    // Deliberately NO notifications permission yet: the banner renders only while
    // `Notification.permission` is still "default" (granted → already on, denied →
    // the prompt is spent), so pre-granting it hides the very thing under test.
    // The grant happens below, after the banner is on screen and before Enable is
    // tapped — which is exactly the state a real user is in.
    ctx = await chromium.launchPersistentContext(profileDir, browserLaunchOptions())
    const page = await ctx.newPage()
    page.on("console", (m) => console.log(`[console.${m.type()}] ${m.text()}`))
    page.on("pageerror", (e) => console.log(`[pageerror] ${e.message}`))

    // Track subscribe POST attempts at the network layer so we can detect even
    // if the client-side push subscribe rejects.
    let subscribePosted = false
    let subscribeStatus: number | null = null
    let subscribeBody: string | null = null
    page.on("request", (req) => {
      if (req.method() === "POST" && req.url().endsWith("/push/subscribe")) {
        subscribePosted = true
      }
    })
    page.on("response", async (res) => {
      if (res.request().method() === "POST" && res.url().endsWith("/push/subscribe")) {
        subscribeStatus = res.status()
        try { subscribeBody = await res.text() } catch { /* swallow */ }
      }
    })

    await page.goto(`${fixture.baseUrl}/pair?t=${encodeURIComponent(fixture.token)}`, { waitUntil: "domcontentloaded" })
    // The wasm bundle boots in ~6 s and then syncs its accessibility mirror on a
    // ~100 ms debounce, so wait for the home list (either shape) rather than a
    // fixed timeout.
    await waitReady(page)

    // Probe Push API surface so DONE_WITH_CONCERNS reporting has detail.
    const apiSurface = await page.evaluate(async () => {
      const hasSW = !!navigator.serviceWorker
      const hasPushMgr = "PushManager" in window
      const hasNotification = typeof Notification !== "undefined"
      let permission: string | null = null
      if (hasNotification) permission = Notification.permission
      let regOk = false
      let pushMgrOk = false
      let subscribeErr: string | null = null
      if (navigator.serviceWorker) {
        try {
          const reg = await navigator.serviceWorker.ready
          regOk = !!reg
          pushMgrOk = !!reg.pushManager
        } catch (e: unknown) { subscribeErr = e instanceof Error ? e.message : String(e) }
      }
      return { hasSW, hasPushMgr, hasNotification, permission, regOk, pushMgrOk, subscribeErr }
    })
    console.log("API surface:", JSON.stringify(apiSurface))

    // The banner sits above the home list. Compose publishes `Text` as the mirror
    // element's innerText, so the label is findable — but it must be TAPPED via
    // dispatchEvent: the canvas over the mirror intercepts real pointer events
    // and `click()` times out. See tests/ui/compose-dom.ts.
    // `exact` matters: the banner's own copy is "Enable notifications for agent
    // replies", and the button is the one whose text is exactly "Enable".
    const enableBtn = page.getByText("Enable", { exact: true }).first()
    await enableBtn.waitFor({ state: "attached", timeout: 30_000 })
    console.log("OK banner visible")

    // Grant now, so the permission dialog Enable opens resolves itself.
    await ctx.grantPermissions(["notifications"], { origin: fixture.baseUrl })
    await enableBtn.dispatchEvent("click")
    console.log("Enable tapped; waiting for subscription POST...")

    // Wait up to 40 s for the row to land in sqlite: the subscribe call goes out
    // to FCM, which regularly takes longer than the 8 s the Vue-era spec allowed.
    const dbPath = join(stateDir, "db.sqlite3")
    const db = new Database(dbPath, { readonly: true })
    let found = false
    for (let i = 0; i < 80; i++) {
      const row = db.prepare("SELECT device FROM push_subscriptions WHERE device = ?").get(fixture.deviceName)
      if (row) { found = true; break }
      await new Promise((r) => setTimeout(r, 500))
    }
    db.close()
    if (!found) {
      if (subscribePosted) {
        console.log(`WARN subscription POST attempted: status=${subscribeStatus} body=${subscribeBody}`)
        throw new Error(`subscribe POST hit (status ${subscribeStatus}) but sqlite row never appeared`)
      }
      throw new Error("push subscription row never appeared in sqlite (client subscribe likely failed; check console for errors)")
    }
    console.log("OK subscription stored in sqlite")
  } finally {
    if (ctx) await ctx.close()
    rmSync(profileDir, { recursive: true, force: true })
  }

  console.log("PUSH UI PASS: banner → Enable → subscription stored")
}

if (import.meta.main) {
  main().catch((e) => {
    console.error("PUSH UI FAILED:", e instanceof Error ? e.stack ?? e.message : String(e))
    process.exit(1)
  })
}
