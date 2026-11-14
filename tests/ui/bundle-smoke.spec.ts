/**
 * #142 bundle smoke: the web client still works after the size cuts (Compose built without source
 * info, extra wasm-opt passes, JetBrains Mono shipped once, brotli on the wire).
 *
 *   1. an unpaired browser lands on the pair gate (WebPairScreen, `web_pair_screen`);
 *   2. a paired one opens the seeded session, and a fenced Kotlin block in the reply renders in the
 *      chat's code editor: its font comes from the TERMINAL's JetBrains Mono path and never the
 *      editor's (the editor's copy is no longer shipped), and with the real syntax module staged
 *      (MUX_SMOKE_REQUIRE_SYNTAX=1) the syntax module loads and the block draws in more than one colour;
 *   3. a Terminal tab opens and the terminal engine wasm loads;
 *   4. every wasm the browser fetched with br offered came back br, and an explicit br request for
 *      the app wasm is answered with the precompressed sibling.
 *
 * Screenshots of each step go to MUX_SMOKE_SHOTS (default: a temp dir, printed).
 *
 * Run: scripts/test-broker.sh bun tests/ui/bundle-smoke.spec.ts
 */
import { mkdirSync, mkdtempSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { launchBrowser, uiFixture, freshPairLink } from "./fixture-env"
import { byTag, byTagPrefix, openSeededSession, tap } from "./compose-dom"

// Browser globals used inside page.evaluate (the test tsconfig has no DOM lib).
declare const createImageBitmap: (blob: Blob) => Promise<{ width: number; height: number }>
declare const OffscreenCanvas: new (width: number, height: number) => {
  getContext(kind: "2d"): { drawImage(image: unknown, x: number, y: number): void; getImageData(x: number, y: number, w: number, h: number): { data: Uint8ClampedArray } } | null
}

type Seen = { url: string; status: number; encoding: string | null; offeredBr: boolean; bytes: number }

const EDITOR_FONT = "dev.supermux.editor.compose.resources/font/"
const TERMINAL_FONT = "dev.supermux.terminal.compose.resources/font/jetbrains_mono_regular.ttf"

function fail(message: string, seen?: Seen[]): never {
  const log = seen ? `\nresponses:\n${seen.map((s) => `  ${s.status} ${s.encoding ?? "-"} ${s.url}`).join("\n")}` : ""
  throw new Error(message + log)
}

/**
 * Distinct strongly-coloured pixels in a PNG, bucketed coarsely: plain text is one colour, highlighted
 * code several. Decoded by the page's own canvas, so the smoke needs no image library.
 */
async function colourCount(page: import("playwright").Page, png: Buffer): Promise<number> {
  return await page.evaluate(async (b64) => {
    const raw = Uint8Array.from(atob(b64), (c) => c.charCodeAt(0))
    const img = await createImageBitmap(new Blob([raw], { type: "image/png" }))
    const canvas = new OffscreenCanvas(img.width, img.height)
    const ctx = canvas.getContext("2d")!
    ctx.drawImage(img, 0, 0)
    const data = ctx.getImageData(0, 0, img.width, img.height).data
    const buckets = new Set<string>()
    for (let i = 0; i < data.length; i += 4) {
      const r = data[i]!, g = data[i + 1]!, b = data[i + 2]!
      if (Math.max(r, g, b) - Math.min(r, g, b) < 60) continue // greys: background, plain text, borders
      buckets.add(`${r >> 5}/${g >> 5}/${b >> 5}`)
    }
    return buckets.size
  }, png.toString("base64"))
}

export async function main(): Promise<void> {
  if (process.env.MUX_RUN_UI_SMOKE !== "1") {
    console.log("skipping bundle smoke; run through scripts/test-broker.sh")
    return
  }
  const requireSyntax = process.env.MUX_SMOKE_REQUIRE_SYNTAX === "1"
  const shots = process.env.MUX_SMOKE_SHOTS ?? mkdtempSync(join(tmpdir(), "bundle-smoke-"))
  mkdirSync(shots, { recursive: true })
  console.log(`bundle smoke: screenshots in ${shots}`)

  const fixture = uiFixture()
  const browser = await launchBrowser()
  const errors: string[] = []
  try {
    // 1. The pair gate.
    const gateContext = await browser.newContext()
    const gate = await gateContext.newPage()
    gate.on("pageerror", (e) => errors.push(`[gate] ${e.message}`))
    await gate.goto(fixture.baseUrl, { waitUntil: "domcontentloaded" })
    await byTag(gate, "web_pair_screen").waitFor({ state: "attached", timeout: 60_000 }).catch(async (e) => {
      await gate.screenshot({ path: join(shots, "1-pair-gate-FAILED.png") }).catch(() => {})
      throw e
    })
    await gate.screenshot({ path: join(shots, "1-pair-gate.png") })
    await gateContext.close()

    // 2. Paired: the chat's code editor.
    const context = await browser.newContext({ viewport: { width: 1280, height: 860 } })
    const page = await context.newPage()
    page.on("pageerror", (e) => errors.push(e.message))
    const seen: Seen[] = []
    page.on("response", async (res) => {
      const offered = (await res.request().allHeaders())["accept-encoding"] ?? ""
      const body = await res.body().catch(() => Buffer.alloc(0))
      seen.push({ url: res.url(), status: res.status(), encoding: res.headers()["content-encoding"] ?? null, offeredBr: /\bbr\b/.test(offered), bytes: body.length })
    })
    await page.goto((await freshPairLink(fixture)).url, { waitUntil: "domcontentloaded" })
    await openSeededSession(page, fixture.sessionId)

    await tap(byTag(page, "composer-input"))
    await page.keyboard.type("smoke:", { delay: 10 })
    for (const line of ["```kotlin", "fun main() { val answer = 42; println(\"hi $answer\") }", "```"]) {
      await page.keyboard.press("Shift+Enter")
      await page.keyboard.type(line, { delay: 5 })
    }
    await tap(byTag(page, "composer-send"))
    const block = byTag(page, "chat_code_editor").last()
    await block.waitFor({ state: "attached", timeout: 30_000 })
    // The face and (when staged) the grammar load asynchronously after the block first composes.
    await page.waitForTimeout(4_000)
    await page.screenshot({ path: join(shots, "2-code-block.png") })

    const fonts = seen.filter((s) => s.url.includes("/composeResources/") && s.url.endsWith(".ttf"))
    if (fonts.some((s) => s.url.includes(EDITOR_FONT))) fail("the editor's own JetBrains Mono was requested; it should come from the terminal's copy", fonts)
    if (!fonts.some((s) => s.url.includes(TERMINAL_FONT) && s.status === 200)) fail("JetBrains Mono (terminal copy) was never loaded for the code block", fonts)

    const grammar = seen.find((s) => /\/kotlin\.sesz(\?|$)/.test(s.url))
    const syntaxWasm = seen.find((s) => /supermux-syntax-[0-9a-f]{8}\.wasm/.test(s.url))
    if (requireSyntax) {
      if (!syntaxWasm || syntaxWasm.status !== 200 || syntaxWasm.bytes === 0) fail("the syntax module did not load", seen)
      // Kotlin's grammar is built into the syntax module; only the code-only grammars fetch a .sesz table.
      if (grammar && grammar.status !== 200) fail(`the Kotlin grammar table answered ${grammar.status}`, seen)
      const box = await block.boundingBox()
      if (!box) fail("the code block has no box on screen")
      const png = await page.screenshot({ clip: box, path: join(shots, "2b-code-block-crop.png") })
      const colours = await colourCount(page, png)
      if (colours < 2) fail(`the code block draws in ${colours} colour(s): no syntax highlighting`)
      console.log(`bundle smoke: highlighting ok (${colours} colour buckets, syntax module ${syntaxWasm.bytes} bytes)`)
    } else {
      console.log(`bundle smoke: syntax module ${syntaxWasm ? `${syntaxWasm.bytes} bytes` : "not requested"} — highlighting not required (MUX_SMOKE_REQUIRE_SYNTAX unset)`)
    }

    // 3. A Terminal tab.
    const addView = page.locator('[id="tab-add-view"], [id="phone_new_tab"]').first()
    await addView.waitFor({ state: "attached", timeout: 30_000 })
    await tap(addView)
    await tap(byTag(page, "new-tab-choice-terminal"))
    await byTagPrefix(page, "terminal-").first().waitFor({ state: "attached", timeout: 30_000 })
    await page.waitForTimeout(3_000)
    await page.screenshot({ path: join(shots, "3-terminal.png") })
    const engine = seen.find((s) => /supermux-terminal-[0-9a-f]{8}\.wasm/.test(s.url))
    if (!engine || engine.status !== 200 || engine.bytes === 0) fail("the terminal engine wasm did not load", seen)

    // 4. Brotli on the wire.
    for (const s of seen.filter((s) => s.url.endsWith(".wasm") && s.offeredBr && s.bytes > 0)) {
      if (s.encoding !== "br") fail(`${s.url} was offered br but came back ${s.encoding ?? "uncompressed"}`, seen)
    }
    const appWasm = seen.find((s) => /supermux-apps-web-[0-9a-f]{8}\.wasm/.test(s.url))
    if (!appWasm) fail("the app wasm was never fetched", seen)
    const direct = await fetch(appWasm.url, { headers: { "accept-encoding": "br" }, decompress: false })
    if (direct.headers.get("content-encoding") !== "br") fail(`an explicit br request for the app wasm got ${direct.headers.get("content-encoding")}`)
    console.log(`bundle smoke: app wasm over br = ${(await direct.arrayBuffer()).byteLength} bytes`)

    const wasmErrors = errors.filter((e) => /wasm|WebAssembly|RuntimeError|CompileError/i.test(e))
    if (wasmErrors.length) fail(`page errors:\n${wasmErrors.join("\n")}`)
    await context.close()
    console.log("bundle smoke: ok")
  } finally {
    await browser.close()
  }
}

if (import.meta.main) {
  main().catch((error) => {
    console.error("BUNDLE SMOKE FAILED:", error instanceof Error ? error.stack ?? error.message : String(error))
    process.exit(1)
  })
}
