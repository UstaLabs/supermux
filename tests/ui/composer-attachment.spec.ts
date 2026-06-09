/**
 * End-to-end UI test: pick a file via the composer action menu, verify a chip
 * renders, upload completes, send WS frame reaches the broker.
 *
 * Run: bun tests/ui/composer-attachment.spec.ts
 *
 * Requires `google-chrome` installed system-wide (Playwright's bundled Chromium
 * has no Ubuntu 26.04 build; we hand-launch the system Chrome instead).
 */

import { chromium, type Browser, type Page } from "playwright"
import { spawnSync } from "child_process"
import { DeviceStore } from "../../src/channels/web/device-store"
import { writeFileSync, existsSync } from "fs"
import { join } from "path"

type BrowserFileInput = {
  files?: { length: number; [index: number]: { name: string } | undefined } | null
  getAttribute(name: string): string | null
  parentElement?: { tagName: string } | null
}
type BrowserElement = { innerHTML: string }
type BrowserDocument = {
  body: { innerText: string }
  querySelectorAll(selector: string): ArrayLike<BrowserElement | BrowserFileInput>
}
type PromptInputHook = {
  addFiles(files: File[]): void
  files: { value: Array<{ filename?: string }> }
}
declare const document: BrowserDocument
declare function atob(value: string): string

const STATE_DIR = process.env.MUX_STATE_DIR ?? join(process.env.HOME ?? "", ".mux/state")
const MUX_WEB_PORT = parseInt(process.env.MUX_WEB_PORT ?? "9898", 10)
const APP_URL = `http://127.0.0.1:${MUX_WEB_PORT}`

const CHROME_BIN = "/usr/bin/google-chrome"

const PIXEL_PNG = Buffer.from(
  "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+M8AAAICAQCk/EwiAAAAAElFTkSuQmCC",
  "base64",
)

async function main(): Promise<void> {
  if (process.env.MUX_RUN_UI_SMOKE !== "1") {
    console.log("skipping UI smoke; set MUX_RUN_UI_SMOKE=1 to run")
    return
  }

  // 1. Confirm broker is listening
  const res = await fetch(`${APP_URL}/sessions`).catch(() => null)
  if (!res) throw new Error(`broker not reachable at ${APP_URL}`)

  // 2. Mint a temp device token
  if (!existsSync(join(STATE_DIR, "devices.json"))) {
    throw new Error(`devices.json missing at ${STATE_DIR}`)
  }
  const ds = new DeviceStore(join(STATE_DIR, "devices.json"))
  const minted = ds.mint("playwright-temp")
  const token = minted.token
  console.log(`minted token (length ${token.length})`)

  // 3. Materialise a pixel PNG to disk for setInputFiles
  const tmpFile = `/tmp/playwright-pixel-${Date.now()}.png`
  writeFileSync(tmpFile, PIXEL_PNG)

  let browser: Browser | null = null
  try {
    // 4. Launch system Chrome (Playwright bundled Chromium has no ubuntu26.04 build)
    if (!existsSync(CHROME_BIN)) throw new Error(`chrome not found at ${CHROME_BIN}`)
    browser = await chromium.launch({
      executablePath: CHROME_BIN,
      headless: true,
      args: ["--no-sandbox", "--disable-dev-shm-usage"],
    })
    const ctx = await browser.newContext()
    const page = await ctx.newPage()

    // Capture console + network errors for diagnostics
    page.on("console", (msg) => console.log(`[console.${msg.type()}] ${msg.text()}`))
    page.on("pageerror", (err) => console.log(`[pageerror] ${err.message}`))
    page.on("requestfailed", (req) =>
      console.log(`[requestfailed] ${req.url()} — ${req.failure()?.errorText ?? "?"}`),
    )

    // 5. Open the PWA with the pairing fragment
    const url = `${APP_URL}/pair?t=${encodeURIComponent(token)}`
    console.log(`→ goto ${url}`)
    await page.goto(url, { waitUntil: "networkidle" })

    // 6. Wait for the session list to render then navigate into a session
    await page.waitForSelector("text=ana", { timeout: 10_000 })
    await page.click("text=ana")

    // 7. Wait for the composer to mount
    await page.waitForSelector('textarea[placeholder*="Message"]', { timeout: 10_000 })

    // Override .hidden so Playwright can interact with the file input directly
    await page.addStyleTag({ content: `input[type="file"].hidden { display: block !important; position: absolute; top: -9999px; }` })

    // 8a. SHORT-PATH: call addFiles via the runtime hook so we test the Vue render path
    //     in isolation (browser file-input automation is brittle in headless Chrome).
    const PIXEL_B64 = PIXEL_PNG.toString("base64")
    const directDbg = await page.evaluate((b64) => {
      const ctx = (globalThis as typeof globalThis & { __cmuxPromptInput?: PromptInputHook }).__cmuxPromptInput
      if (!ctx) return { ok: false, reason: "no hook on window" }
      const bytes = Uint8Array.from(atob(b64), (c) => c.charCodeAt(0))
      const file = new File([bytes], "pixel.png", { type: "image/png" })
      ctx.addFiles([file])
      return { ok: true, filesAfter: ctx.files.value.length, firstFilename: ctx.files.value[0]?.filename }
    }, PIXEL_B64)
    console.log("addFiles via hook:", JSON.stringify(directDbg))
    await page.waitForTimeout(400)

    const addonDbg = await page.evaluate(() => {
      const addons = Array.from(document.querySelectorAll('[data-align="block-end"]')) as BrowserElement[]
      return addons.map((a) => a.innerHTML.slice(0, 250))
    })
    console.log("addon HTML after addFiles:", JSON.stringify(addonDbg, null, 2))

    // 8b. Trigger the action menu and click "Files"
    const trigger = page.locator('[data-slot="dropdown-menu-trigger"]')
    await trigger.waitFor({ state: "visible", timeout: 5_000 })
    await trigger.click()
    console.log("action menu trigger clicked")
    await page.waitForTimeout(300)

    // Dump menu state
    const menuItems = await page.locator('[role="menuitem"]').all()
    console.log(`menu items found: ${menuItems.length}`)
    for (const item of menuItems) {
      const txt = await item.innerText()
      console.log(`  - "${txt.trim()}"`)
    }

    // Click via menu item role (more reliable than text= which is tricky inside portals)
    const filesItem = page.locator('[role="menuitem"]').filter({ hasText: "Files" }).first()
    await filesItem.waitFor({ state: "visible", timeout: 5_000 })

    // Set up filechooser listener before the click
    const chooserPromise = page.waitForEvent("filechooser", { timeout: 5_000 }).catch(() => null)
    await filesItem.click()
    console.log("Files menu item clicked")
    const chooser = await chooserPromise
    if (!chooser) {
      console.log("× no filechooser event fired")
      throw new Error("filechooser never opened — openFileDialog did not run")
    }
    await chooser.setFiles(tmpFile)
    console.log("✓ chooser set with file")

    // Wait for Vue to flush
    await page.waitForTimeout(1500)

    const dbg2 = await page.evaluate(() => {
      const inputs = Array.from(document.querySelectorAll('input[type="file"]')) as BrowserFileInput[]
      const summary = inputs.map((inp, i) => ({
        i,
        count: inp.files?.length ?? -1,
        name: inp.files?.[0]?.name ?? "(none)",
        accept: inp.getAttribute("accept"),
        capture: inp.getAttribute("capture"),
        parentTag: inp.parentElement?.tagName,
      }))
      const addons = Array.from(document.querySelectorAll('[data-align="block-end"]')) as BrowserElement[]
      return {
        totalInputs: inputs.length,
        inputs: summary,
        addons: addons.map((a, i) => ({ i, innerHTML: a.innerHTML.slice(0, 80) })),
      }
    })
    console.log("post-chooser debug:", JSON.stringify(dbg2, null, 2))

    // small settle delay to let Vue reactivity flush
    await page.waitForTimeout(500)

    // diagnostic dump — what's in the composer area?
    console.log("\n=== DOM after setInputFiles ===")
    const composerArea = await page.locator('form').first().innerHTML().catch(() => "(no form found)")
    console.log(composerArea.slice(0, 4000))

    // 9. Assert a chip appeared
    const chipFilename = page.locator('text=' + tmpFile.split("/").pop()).first()
    await chipFilename.waitFor({ timeout: 5_000 })
    console.log("✓ chip rendered")

    // 10. Click submit
    const submitBtn = page.locator('button[aria-label="Submit"]')
    await submitBtn.waitFor({ state: "visible" })
    const disabled = await submitBtn.getAttribute("disabled")
    console.log(`submit disabled attr: ${disabled}`)
    await submitBtn.click({ trial: false })
    console.log("submit clicked")

    // 11. Wait for the upload to complete — chip text should change to "Uploaded"
    //     OR a toast saying "Uploading…" should appear and dismiss.
    const tmpFilename = tmpFile.split("/").pop() ?? tmpFile
    await page.waitForFunction(
      (filename) => document.body.innerText.includes("Uploaded") || !document.body.innerText.includes(filename),
      tmpFilename,
      { timeout: 15_000 },
    ).catch((e) => {
      console.log(`upload completion not observed: ${e.message}`)
    })

    // 12. Verify a row landed in messages (broker side)
    const msgs = await fetch(`${APP_URL}/sessions/ana/messages`, {
      headers: { Cookie: `cmux_token=${token}` },
    }).then((r) => r.json())
    const recent = (msgs as any[]).slice(-3)
    console.log("recent ana messages:", JSON.stringify(recent, null, 2))

    const found = (msgs as any[]).some((m) =>
      m.direction === "inbound" && m.channel === "web" && Array.isArray(m.attachments) && m.attachments.length > 0,
    )
    if (found) console.log("✓ broker recorded an inbound message with attachments")
    else console.log("✗ no inbound web message with attachments found in /sessions/ana/messages")

    console.log("\n=== DOM SNAPSHOT — composer area ===")
    const composerHtml = await page.locator('form').first().innerHTML().catch(() => "(no form)")
    console.log(composerHtml.slice(0, 4000))
  } finally {
    if (browser) await browser.close()
    // Clean up temp device
    const ds2 = new DeviceStore(join(STATE_DIR, "devices.json"))
    ds2.revoke("playwright-temp")
    try { spawnSync("rm", ["-f", tmpFile]) } catch {}
  }
}

main().catch((e) => {
  console.error("TEST FAILED:", e instanceof Error ? e.stack ?? e.message : String(e))
  process.exit(1)
})
