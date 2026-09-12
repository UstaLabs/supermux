/**
 * Driving Compose-for-Web from Playwright.
 *
 * The wasm client paints into a `<canvas>`; everything Playwright can see is the
 * ACCESSIBILITY MIRROR Compose maintains beside it — a tree of empty elements
 * under `div#cmp_a11y_root`, itself inside an OPEN shadow root on `<body>`'s
 * single anonymous div. Three consequences shape every journey, and they are the
 * reason this file exists instead of the selectors being inlined:
 *
 *  1. `Modifier.testTag("x")` becomes the mirror element's `id` — so `#x`, and
 *     `[id="a:b"]` for the ids that contain a colon (`chat-message:outbound:m1`),
 *     which CSS would otherwise read as a pseudo-class.
 *  2. **`locator.click()` never works.** The canvas sits over the mirror and
 *     intercepts pointer events, so Playwright's actionability check fails with
 *     "<canvas …> intercepts pointer events" and times out after 30 s, every
 *     time. Compose installs a REAL DOM `click` listener on every clickable
 *     node, so `dispatchEvent("click")` drives the app exactly as a tap does.
 *     [tap] is the only sanctioned way to press something.
 *  3. Playwright locators pierce the open shadow root; `page.evaluate(() =>
 *     document.querySelector("#x"))` does NOT. In-page code has to walk
 *     `.shadowRoot` itself — so prefer locators, and keep `page.evaluate` for
 *     `fetch` calls against the broker.
 *
 * The mirror is also rebuilt on a ~100 ms debounce and the wasm bundle takes
 * ~6 s to boot, so every wait here polls with a generous timeout instead of
 * sleeping.
 */
import type { Locator, Page } from "playwright"

/** Ids with a `:` need the attribute form — `#chat-message:outbound:m1` is invalid CSS. */
export function byTag(page: Page, id: string): Locator {
  return page.locator(`[id="${id}"]`)
}

/** Every mirror element whose id starts with [prefix] (`chat-message:outbound:`). */
export function byTagPrefix(page: Page, prefix: string): Locator {
  return page.locator(`[id^="${prefix}"]`)
}

/**
 * Press something. See note 2 above: NEVER call `locator.click()` on a mirror
 * element — the canvas intercepts the pointer and the call times out.
 */
export async function tap(target: Locator): Promise<void> {
  await target.waitFor({ state: "attached", timeout: 30_000 })
  await target.dispatchEvent("click")
}

/**
 * Focus the field with [id] and type [text] on the keyboard.
 *
 * Compose owns its own text state, so `fill()` (which writes `value`/textContent
 * on the DOM node) is a no-op here — the only input the app actually sees is real
 * key events, which is what `page.keyboard.type` sends.
 */
export async function typeInto(page: Page, id: string, text: string): Promise<void> {
  const field = byTag(page, id)
  await field.waitFor({ state: "attached", timeout: 30_000 })
  await tap(field)
  await page.keyboard.type(text, { delay: 10 })
}

/**
 * Wait for the app to be usable: the boot is ~6 s of wasm instantiation plus a
 * debounced a11y sync, and the home list is `workspaces_list` when workspaces are
 * on (what `scripts/test-broker.sh` seeds) and `session-list` when they are off.
 * Accept either so the journeys do not encode the fixture's workspace policy.
 */
export async function waitReady(page: Page, timeoutMs = 60_000): Promise<void> {
  await page
    .locator('[id="workspaces_list"], [id="session-list"]')
    .first()
    .waitFor({ state: "attached", timeout: timeoutMs })
}

/** The rendered text of a mirror subtree — Compose publishes `Text` as `innerText`. */
export async function innerTextOf(target: Locator): Promise<string> {
  return (await target.innerText()).trim()
}

/**
 * Poll [target]'s rendered text until it contains [needle], and return it.
 *
 * The mirror is rebuilt on a ~100 ms debounce and some of what a journey waits
 * for (an ASR round trip) takes seconds, so this polls instead of asserting once.
 * A locator read is used rather than `page.evaluate`, which cannot reach into the
 * shadow root the mirror lives in.
 */
export async function waitForTextWithin(
  target: Locator,
  needle: string,
  timeoutMs = 30_000,
): Promise<string> {
  const deadline = Date.now() + timeoutMs
  let seen = ""
  while (Date.now() < deadline) {
    seen = await innerTextOf(target).catch(() => "")
    if (seen.includes(needle)) return seen
    await target.page().waitForTimeout(250)
  }
  throw new Error(`timed out waiting for ${JSON.stringify(needle)}; last text was ${JSON.stringify(seen)}`)
}

/**
 * Open the seeded session: a workspace row when workspaces are on, a session row
 * when they are not, then wait for the chat view (`view_chat` — there is no
 * `chat-view` id in the Compose tree) and its composer.
 */
export async function openSeededSession(page: Page, sessionId: string): Promise<void> {
  await waitReady(page)
  const workspaceRow = byTagPrefix(page, "workspace_row_").first()
  const sessionRow = byTag(page, `session-row:${sessionId}`)
  const row = page.locator(`[id^="workspace_row_"], [id="session-row:${sessionId}"]`).first()
  await row.waitFor({ state: "attached", timeout: 30_000 })
  await tap((await workspaceRow.count()) > 0 ? workspaceRow : sessionRow)
  await byTag(page, "view_chat").waitFor({ state: "attached", timeout: 30_000 })
  await byTag(page, "composer-input").waitFor({ state: "attached", timeout: 30_000 })
}
