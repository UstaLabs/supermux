# Browser tab (#105) steps 3–4 — the Browser tab in the app — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A Browser tab in every workspace. It shows a real web page (webview-compose) with an address bar, back, forward, reload, find and zoom, and its title and icon on its tab. Its page is synced across clients. Chat links open in it, and a ⌘/Ctrl-click opens the system browser instead. A localhost port opens through the host's preview link. It is row 6 of the New tab page, on phones too.

**Architecture:**
- **Pure rules in `:shared`**, tested in commonTest: what an address means, where a link opens, what a tab writes back to its view, the zoom steps (`dev.supermux.browser`, `dev.supermux.workspace`).
- **One Compose pane in `:ui`** (`dev.supermux.ui.browser`) around webview-compose's `WebView`, tested on the JVM with the library's fake engine.
- **Placement belongs to the workspace.** A `WorkspaceBrowserOpener` sits beside the file opener and shares its placement mechanics.
- **Chat links reach the opener through a CompositionLocal**, so the timeline gains no parameters.

**Tech Stack:** Kotlin Multiplatform, Compose Multiplatform 1.12.2, webview-compose (its testing module's `FakeWebEngineFactory` in tests), kotlinx.serialization, kotlin.test, Compose UI tests (`runComposeUiTest`).

**Branch:** `mux/browser-tab`. It is dev `f88fd885e` with two branches merged in: `mux/browser-library-mac` (step 2a: find, zoom, cookie, inspector and favicon in the library) and `mux/browser-tab-logic` (the pure address, link and state rules). Its car goes to Muxie after 2a's car has landed. #105's car 2 (the preview-only token, `POST /devices/preview-token`) must be live for Task 8's cookie to work against a real broker; the tests don't need it.

**Decisions this follows** (card #105, Ahmet 2026-11-07, and the prototype's defaults):
- Every chat link opens in a Browser tab, and ⌘/Ctrl-click opens the system browser.
- A tab is reused only when it shows the same link; otherwise a new tab opens.
- A preview-only token, scoped to /p/, lets a tab open private previews.
- `localhost:<port>` opens through that port's preview link.
- On the web client, chat links open a new tab of the real browser.
- Unexposed ports get the offer page from the proposal sent to Tally on 2026-11-08: "Port 5173 runs on ‹host›, not on this device. [Make a private link]". If Ahmet answers otherwise, Task 8's offer page changes and nothing else does.

**Out of this plan:**
- Desktop packaging: the macOS glue in the DMG, the Windows DLL in CI, the WPE runtime in the .deb (`2026-11-08-browser-tab-packaging.md`).
- Agent pages, the `{doc}` state (#104).
- Moving the tab's shortcuts into #96's registry. That is a follow-up once #96 phase 2 is on dev; until then the pane handles its keys itself, as the New tab page does.
- Find, zoom and cookies on Android, iOS, Windows, Linux and the web (plans 2b–2d). Until then those engines answer "can't", and the pane says so.

**How to run things.** The box is shared, so Gradle always runs under the heavy lock, from `apps/`:

```bash
flock -w 3600 /tmp/supermux-heavy.lock systemd-run --user --scope -q -p MemoryMax=8G -- nice ./gradlew <tasks> --max-workers=2
```

- Shared tests: `:shared:jvmTest --tests '<pattern>'`.
- UI tests: `:ui:jvmTest --tests '<pattern>'`. `:ui` has no commonTest; its jvmTest covers commonMain.
- Never run a broad `bun test`, and never the scrcpy test that drives Ahmet's Fold.

---

## File structure

**Create:**
- `apps/shared/src/commonMain/kotlin/dev/supermux/browser/BrowserTabSync.kt`: what a tab writes to its view (`BrowserPatch`), and whether a stored page is another client's.
- `apps/shared/src/commonMain/kotlin/dev/supermux/browser/BrowserZoom.kt`: the zoom steps.
- `apps/shared/src/commonMain/kotlin/dev/supermux/workspace/WorkspaceViewPlacement.kt`: `placeNewView` and `viewIdsOfGroup`, moved out of the file opener so the Browser opener shares them.
- `apps/shared/src/commonMain/kotlin/dev/supermux/workspace/WorkspaceBrowserOpen.kt`: `planBrowserOpen` and `WorkspaceBrowserOpener`.
- `apps/ui/src/commonMain/kotlin/dev/supermux/ui/browser/BrowserActions.kt`: the broker seams, and their `HostStore` builder.
- `apps/ui/src/commonMain/kotlin/dev/supermux/ui/browser/AppWebViews.kt`: the app's one `WebViewRuntime`, made on first use, which tests replace.
- `apps/ui/src/commonMain/kotlin/dev/supermux/ui/browser/LinkOpener.kt`: `LocalLinkOpener`, `LocalOpenLink` and click-modifier tracking.
- `apps/ui/src/commonMain/kotlin/dev/supermux/ui/browser/BrowserPane.kt`: the pane.
- `apps/ui/src/commonMain/kotlin/dev/supermux/ui/browser/BrowserBars.kt`: the toolbar, the find bar, the offer page and the empty states.
- Tests:
  - shared: `browser/BrowserTabSyncTest.kt`, `browser/AddressActionTest.kt`, `browser/BrowserZoomTest.kt`, `workspace/WorkspaceBrowserOpenTest.kt`;
  - ui: `browser/BrowserPaneTest.kt`, `browser/LinkOpenerTest.kt`, `shell/BrowserKindTest.kt`.

**Modify:**
- `apps/ui/build.gradle.kts`: the two dependencies.
- `.github/workflows/ci.yml`: three path filters.
- `apps/shared/.../workspace/NewViewKind.kt`, `WorkspaceSingletonView.kt`, `ViewTitle.kt`, `WorkspaceFileOpen.kt`.
- `apps/shared/.../state/HostStore.kt`: `addWorkspaceView`'s state table.
- `apps/shared/.../browser/BrowserAddress.kt`: `AddressAction`.
- `apps/shared/.../net/BrokerApi.kt`: `previewToken()` and its DTOs.
- `apps/shared/.../util/TransportPolicy.kt`: `isLoopback`.
- `apps/ui/.../shell/`: `WorkspaceModel.kt`, `NewTabs.kt`, `NewTabGlyphs.kt`, `CloseViewDialog.kt`, `WorkspacePanes.kt`, `PhoneTabSwitcher.kt`, `ViewHost.kt`, `ShellActions.kt`, `SupermuxApp.kt`.
- `apps/ui/.../workspace/WorkspaceSession.kt`.
- `apps/ui/.../chat/Markdown.kt`.
- `apps/ui/.../platform/Platform.kt`: `Caps.linksInBrowserTabs`.
- `apps/web/.../WebPlatform.kt`.

---

### Task 1: `:ui` depends on webview-compose, and CI's app lanes see it

**Files:**
- Modify: `apps/ui/build.gradle.kts` (`commonMain.dependencies`, after `implementation(project(":editor-plugins:lint"))`; `jvmTest.dependencies`)
- Modify: `.github/workflows/ci.yml:105-121` (the `web`, `desktop` and `ios` filters)

- [ ] **Step 1: Add the dependencies.** In `apps/ui/build.gradle.kts`, after `implementation(project(":editor-plugins:lint"))`:

```kotlin
            // The Browser tab (#105): each platform's own web engine inside Compose. `implementation`:
            // no webview type is in this module's public signatures (BrowserActions speaks the
            // broker's DTOs; the pane converts).
            implementation(project(":webview-compose"))
```

and in `jvmTest.dependencies { … }`, after `implementation(compose.desktop.currentOs)`:

```kotlin
            // The library's fake engine, so the Browser pane's tests run where Compose UI tests run.
            implementation(project(":webview-compose-testing"))
```

- [ ] **Step 2: Let the app lanes see the library.** In `.github/workflows/ci.yml`, add `- 'apps/webview-compose/**'` to the `web` filter (after `- 'apps/gradle/**'`), the `desktop` filter (after `- 'apps/ui/**'`) and the `ios` filter (after `- 'apps/ui/**'`). Put this comment above the first one:

```yaml
              # :ui depends on the webview library (#105): a change to it can break the app.
```

- [ ] **Step 3: Compile every target this box builds.**

Run: `… ./gradlew :ui:compileKotlinJvm :ui:compileKotlinWasmJs :ui:compileDebugKotlinAndroid --max-workers=2`
Expected: BUILD SUCCESSFUL. iOS compiles on the Mac and in CI's `ios-tests`.

- [ ] **Step 4: Commit**

```bash
git add apps/ui/build.gradle.kts .github/workflows/ci.yml
git commit -m "build(ui): depend on webview-compose for the Browser tab; CI's app lanes watch the library (#105)"
```

---

### Task 2: The Browser kind, everywhere the app names a kind (row 6)

**Files:**
- Modify: `apps/shared/src/commonMain/kotlin/dev/supermux/workspace/NewViewKind.kt`
- Modify: `apps/shared/src/commonMain/kotlin/dev/supermux/workspace/WorkspaceSingletonView.kt:28-34`
- Modify: `apps/shared/src/commonMain/kotlin/dev/supermux/workspace/ViewTitle.kt`
- Modify: `apps/shared/src/commonMain/kotlin/dev/supermux/state/HostStore.kt:1716-1730`
- Modify: `apps/ui/src/commonMain/kotlin/dev/supermux/ui/shell/WorkspaceModel.kt:73-89`
- Modify: `apps/ui/src/commonMain/kotlin/dev/supermux/ui/shell/NewTabs.kt:503-517`
- Modify: `apps/ui/src/commonMain/kotlin/dev/supermux/ui/shell/NewTabGlyphs.kt`
- Modify: `apps/ui/src/commonMain/kotlin/dev/supermux/ui/shell/CloseViewDialog.kt:97-105`
- Modify: `apps/ui/src/commonMain/kotlin/dev/supermux/ui/shell/WorkspacePanes.kt:1383-1398`
- Modify: `apps/ui/src/commonMain/kotlin/dev/supermux/ui/shell/PhoneTabSwitcher.kt:189-199`
- Test: `apps/shared/src/commonTest/kotlin/dev/supermux/workspace/ViewTitleTest.kt`, `WorkspaceSingletonViewTest.kt`
- Test: `apps/ui/src/jvmTest/kotlin/dev/supermux/ui/shell/BrowserKindTest.kt` (new)

- [ ] **Step 1: Write the failing tests.** Append to `ViewTitleTest`:

```kotlin
    @Test
    fun aBrowserTabIsNamedAfterItsPage() {
        // The pane writes the page's title to the view, so every client's tab shows it.
        assertEquals("Vite", viewTitle(view("browser", mapOf("url" to "https://vitejs.dev/guide/")).copy(title = "Vite")))
        // Before the page has a title: its site, or the port on the host.
        assertEquals("vitejs.dev", viewTitle(view("browser", mapOf("url" to "https://vitejs.dev/guide/"))))
        assertEquals("localhost:5173", viewTitle(view("browser", mapOf("url" to "http://localhost:5173/login"))))
        assertEquals("New tab", viewTitle(view("browser", emptyMap())))
    }
```

Append to `WorkspaceSingletonViewTest` (add `import kotlin.test.assertFalse` if the file lacks it):

```kotlin
    @Test
    fun aBrowserTabIsNeverASingleton() {
        assertFalse(NewViewKind.BROWSER.singleton)
        assertFalse(ViewDto(id = "b1", workspaceId = "w1", kind = "browser").matchesKind(NewViewKind.BROWSER))
    }
```

Create `apps/ui/src/jvmTest/kotlin/dev/supermux/ui/shell/BrowserKindTest.kt`:

```kotlin
package dev.supermux.ui.shell

import dev.supermux.proto.ViewDto
import dev.supermux.workspace.NewViewKind
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BrowserKindTest {
    @Test fun browserIsTheSixthRowOnBothPages() {
        assertEquals(NewViewKind.BROWSER, NewViewKind.entries[5])
        assertEquals("Browser", NewViewKind.BROWSER.label)
        assertTrue(NewViewKind.BROWSER in phoneAddKinds())
    }

    @Test fun aNewBrowserTabStartsWithNoPage() {
        // `{}`: the broker's "new tab" state (browserStateError accepts it).
        assertEquals(JsonObject(emptyMap()), addViewState(NewViewKind.BROWSER))
    }

    @Test fun closingABrowserTabAsksNothing() {
        // Its page is a link: closing it stops nothing.
        assertFalse(closeNeedsConfirmation("browser"))
        assertTrue(closeNeedsConfirmation("terminal"))
    }

    @Test fun aBrowserTabHasTheGlobeOnTheSwitcher() {
        assertEquals(androidx.compose.material.icons.Icons.Filled.Language, ViewDto(id = "b", workspaceId = "w", kind = "browser").switcherIcon())
    }
}
```

- [ ] **Step 2: Run them to see them fail**

Run: `… ./gradlew :shared:jvmTest --tests 'dev.supermux.workspace.ViewTitleTest' --tests 'dev.supermux.workspace.WorkspaceSingletonViewTest' :ui:jvmTest --tests 'dev.supermux.ui.shell.BrowserKindTest' --max-workers=2`
Expected: compilation FAILS with `Unresolved reference 'BROWSER'`.

- [ ] **Step 3: The kind.** In `NewViewKind.kt`, change the KDoc's "and of their keys 1–5" to "and of their keys 1–6", and add after `DISPLAY("display", "Display"),`:

```kotlin
    /** A web page (#105): a site, or a port on the host through its preview link. Several per workspace. */
    BROWSER("browser", "Browser"),
```

- [ ] **Step 4: The exhaustive `when`s the compiler now names.**
  - `WorkspaceSingletonView.kt`, in `matchesKind`: replace `NewViewKind.CHAT, NewViewKind.TERMINAL, NewViewKind.DISPLAY -> false` with `NewViewKind.CHAT, NewViewKind.TERMINAL, NewViewKind.DISPLAY, NewViewKind.BROWSER -> false`.
  - `HostStore.kt`, in `addWorkspaceView`'s `when (kind)`, before the `CHAT` branch:

```kotlin
            // A new tab: no page yet (BrowserViewState.Empty), the address bar takes the focus.
            dev.supermux.workspace.NewViewKind.BROWSER -> buildJsonObject { }
```

  - `WorkspaceModel.kt`: add `NewViewKind.BROWSER,` as the last entry of `phoneAddKinds()`'s list, and in `addViewState`, before the `CHAT` branch:

```kotlin
    NewViewKind.BROWSER -> buildJsonObject { }
```

  - `NewTabs.kt`, in `newTabChoices`: add `NewViewKind.BROWSER -> NewTabGlyphs.Browser` to the `icon` `when`, and `NewViewKind.BROWSER -> "A web page, or an app running on a port"` to the `detail` `when`.
  - `NewTabGlyphs.kt`: after `Display`, in the file's own style (stroked paths on the 24-unit grid, `glyph(name) { … }`):

```kotlin
    /** A globe: the page's circle, a meridian and the equator. */
    val Browser: ImageVector by lazy {
        glyph("browser") {
            moveTo(20f, 12f)
            arcTo(8f, 8f, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = 4f, y1 = 12f)
            arcTo(8f, 8f, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = 20f, y1 = 12f)
            close()
            moveTo(12f, 4f)
            arcTo(3.6f, 8f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 12f, y1 = 20f)
            arcTo(3.6f, 8f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 12f, y1 = 4f)
            moveTo(4f, 12f)
            lineTo(20f, 12f)
        }
    }
```

- [ ] **Step 5: The string switches.**
  - `ViewTitle.kt`: add `"browser" -> browserTabTitle(view)` before `"display" -> "Display"`, the imports `dev.supermux.browser.BrowserViewState`, `dev.supermux.browser.DisplayAddress` and `dev.supermux.browser.displayAddress`, and at the end of the file:

```kotlin
/** A Browser tab before its page has a title: the site, the port on the host, or a new tab. */
private fun browserTabTitle(view: ViewDto): String = when (val state = BrowserViewState.from(view.state)) {
    is BrowserViewState.Page -> when (val shown = displayAddress(state.url, emptyList())) {
        is DisplayAddress.Local -> "localhost:${shown.port}"
        is DisplayAddress.Preview -> "port ${shown.port}"
        is DisplayAddress.Web -> shown.host
    }
    // An agent page (#104) names itself through the view's title.
    is BrowserViewState.Doc -> "Page"
    BrowserViewState.Empty -> "New tab"
}
```

  - `CloseViewDialog.kt`: replace `fun closeNeedsConfirmation(kind: String): Boolean = kind != "editor"` with the following, and add one sentence to its KDoc: "A Browser tab's close is silent too: its page is a link, and closing it stops nothing."

```kotlin
fun closeNeedsConfirmation(kind: String): Boolean = kind != "editor" && kind != "browser"
```

  - `WorkspacePanes.kt`, in `ViewTabIcon`'s `when`: add `v?.kind == "browser" -> Icons.Outlined.Language` after the `display` line, and `import androidx.compose.material.icons.outlined.Language`. Task 10 adds the page's own icon.
  - `PhoneTabSwitcher.kt`, in `switcherIcon`: add `"browser" -> Icons.Filled.Language` after `"display"`, and `import androidx.compose.material.icons.filled.Language`.

- [ ] **Step 6: Run the tests, then the New tab page's own tests**

Run: `… ./gradlew :shared:jvmTest --tests 'dev.supermux.workspace.*' :ui:jvmTest --tests 'dev.supermux.ui.shell.BrowserKindTest' --tests 'dev.supermux.ui.shell.NewTabPageTest' --tests 'dev.supermux.ui.shell.NewTabsTest' --tests 'dev.supermux.ui.shell.CloseViewDialogTest' --max-workers=2`
Expected: PASS. `NewTabPageTest.everyKindHasARow` now finds `new-tab-choice-browser` as well.

- [ ] **Step 7: Commit**

```bash
git add apps/shared apps/ui
git commit -m "feat(browser): a Browser kind in the New tab page (row 6), its title, icon and silent close (#105)"
```

---

### Task 3: The tab's pure rules: what it writes back, what an address does, the zoom steps

**Files:**
- Create: `apps/shared/src/commonMain/kotlin/dev/supermux/browser/BrowserTabSync.kt`
- Create: `apps/shared/src/commonMain/kotlin/dev/supermux/browser/BrowserZoom.kt`
- Modify: `apps/shared/src/commonMain/kotlin/dev/supermux/browser/BrowserAddress.kt` (append `AddressAction`)
- Test: `apps/shared/src/commonTest/kotlin/dev/supermux/browser/BrowserTabSyncTest.kt`, `AddressActionTest.kt`, `BrowserZoomTest.kt`

- [ ] **Step 1: Write the failing tests.**

`BrowserTabSyncTest.kt`:

```kotlin
package dev.supermux.browser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BrowserTabSyncTest {
    private fun page(url: String) = BrowserViewState.page(url)!!

    @Test fun aCommittedPageIsWrittenOnceAndAgainWhenItsTitleArrives() {
        val sync = BrowserTabSync()
        assertEquals(BrowserPatch(page("https://vitejs.dev/").toJson(), null), sync.committed("https://vitejs.dev/", null))
        assertNull(sync.committed("https://vitejs.dev/", null), "nothing new")
        assertEquals("Vite", sync.committed("https://vitejs.dev/", "  Vite ")?.title)
        assertNull(sync.committed("https://vitejs.dev/", "Vite"))
    }

    @Test fun aPageTheBrokerWouldRefuseIsNeverWritten() {
        val sync = BrowserTabSync()
        assertNull(sync.committed(null, "x"))
        assertNull(sync.committed("about:blank", "x"))
        assertNull(sync.committed("javascript:alert(1)", "x"))
    }

    @Test fun aLongTitleIsCut() {
        assertEquals(200, BrowserTabSync().committed("https://a.test/", "t".repeat(500))?.title?.length)
    }

    @Test fun aNewTabOpensTheStoredPage() {
        assertEquals("https://vitejs.dev/", BrowserTabSync().toFollow(page("https://vitejs.dev/"), showing = null))
        assertNull(BrowserTabSync().toFollow(BrowserViewState.Empty, showing = null))
    }

    @Test fun theTabsOwnEchoIsNeverFollowedEvenAfterItMovedOn() {
        val sync = BrowserTabSync()
        sync.committed("https://a.test/", null)
        sync.committed("https://b.test/", null)
        // The broker's echo of the first write arrives while the tab already shows the second page.
        assertNull(sync.toFollow(page("https://a.test/"), showing = "https://b.test/"))
    }

    @Test fun anotherClientsPageIsFollowed() {
        val sync = BrowserTabSync()
        sync.committed("https://a.test/", null)
        assertEquals("https://c.test/", sync.toFollow(page("https://c.test/"), showing = "https://a.test/"))
        assertNull(sync.toFollow(page("https://a.test/"), showing = "https://a.test/"), "already there")
        assertNull(sync.toFollow(BrowserViewState.Empty, showing = "https://a.test/"), "a cleared state never unloads a page")
    }
}
```

`AddressActionTest.kt`:

```kotlin
package dev.supermux.browser

import dev.supermux.net.ProxyDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AddressActionTest {
    private val preview = ProxyDto(domain = "p-5173", sessionName = "fix-login", port = 5173, url = "https://relay.test/p/p-5173/")

    @Test fun aSiteLoads() {
        assertEquals(AddressAction.Load("https://vitejs.dev"), addressAction("vitejs.dev", emptyList(), onHost = false))
    }

    @Test fun aPortIsTheHostsOwnOnTheHost() {
        assertEquals(AddressAction.Load("http://localhost:5173/login"), addressAction("localhost:5173/login", emptyList(), onHost = true))
    }

    @Test fun aPortElsewhereOpensItsPreview() {
        assertEquals(AddressAction.Load("https://relay.test/p/p-5173/login"), addressAction("http://localhost:5173/login", listOf(preview), onHost = false))
    }

    @Test fun aPortWithNoPreviewIsAnOffer() {
        assertEquals(AddressAction.Offer(3000, "/"), addressAction("3000", listOf(preview), onHost = false))
    }

    @Test fun textThatIsntAnAddressSaysSo() {
        val said = addressAction("how do I deploy", emptyList(), onHost = false)
        assertTrue(said is AddressAction.Say && "isn't an address" in said.message)
    }
}
```

`BrowserZoomTest.kt`:

```kotlin
package dev.supermux.browser

import kotlin.test.Test
import kotlin.test.assertEquals

class BrowserZoomTest {
    @Test fun zoomStepsThroughTheBrowsersLevels() {
        assertEquals(1.1f, nextZoom(1f, +1))
        assertEquals(0.9f, nextZoom(1f, -1))
        assertEquals(1.25f, nextZoom(1.17f, +1), "a level between steps goes to the next one")
    }

    @Test fun zoomStopsAtEitherEnd() {
        assertEquals(5f, nextZoom(5f, +1))
        assertEquals(0.25f, nextZoom(0.25f, -1))
    }
}
```

- [ ] **Step 2: Run them to see them fail**

Run: `… ./gradlew :shared:jvmTest --tests 'dev.supermux.browser.*' --max-workers=2`
Expected: compilation FAILS: `BrowserTabSync`, `AddressAction`, `nextZoom` unresolved.

- [ ] **Step 3: Implement.** `BrowserTabSync.kt`:

```kotlin
package dev.supermux.browser

import kotlinx.serialization.json.JsonObject

/** One write of a Browser tab to its view: the page as the view's state, and the page's title. */
data class BrowserPatch(val state: JsonObject, val title: String?)

/**
 * What a Browser tab writes to its view, and what it makes of the view's state when that changes (#105).
 *
 * The tab writes each page it commits, and every client follows the page stored. The broker echoes a tab's own
 * writes back, sometimes after the user has gone on to another page, so a page this tab wrote lately is its own echo,
 * never a page to go back to. (Another client storing one of those pages isn't followed either: rare, and better than
 * a tab that jumps back.)
 */
class BrowserTabSync(private val remembered: Int = 8) {
    private val written = ArrayDeque<String>()
    private var last: BrowserPatch? = null

    /** The tab committed [url], with [title] once the page has one: the write to send, or null when there's nothing new. */
    fun committed(url: String?, title: String?): BrowserPatch? {
        val page = url?.let(BrowserViewState::page) ?: return null
        val patch = BrowserPatch(page.toJson(), title?.trim()?.take(MAX_TITLE)?.ifEmpty { null })
        if (patch == last) return null
        last = patch
        written.remove(page.url)
        written.addLast(page.url)
        while (written.size > remembered) written.removeFirst()
        return patch
    }

    /** The view's [stored] state: the page this tab should load, or null when it shows it already or wrote it itself. */
    fun toFollow(stored: BrowserViewState, showing: String?): String? {
        val url = (stored as? BrowserViewState.Page)?.url ?: return null
        return url.takeIf { it != showing && it !in written }
    }

    private companion object {
        const val MAX_TITLE = 200
    }
}
```

`BrowserZoom.kt`:

```kotlin
package dev.supermux.browser

/** The zoom levels a Browser tab steps through, as browsers do. */
val ZOOM_STEPS: List<Float> = listOf(0.25f, 0.33f, 0.5f, 0.67f, 0.75f, 0.8f, 0.9f, 1f, 1.1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f, 4f, 5f)

/** The next level from [current], up when [direction] is positive, else down; the same level at either end. */
fun nextZoom(current: Float, direction: Int): Float =
    if (direction > 0) ZOOM_STEPS.firstOrNull { it > current + 0.001f } ?: ZOOM_STEPS.last()
    else ZOOM_STEPS.lastOrNull { it < current - 0.001f } ?: ZOOM_STEPS.first()
```

Append to `BrowserAddress.kt`:

```kotlin
/** What a Browser tab does with an address: load it, offer a private link for a port, or say why it can't. */
sealed interface AddressAction {
    data class Load(val url: String) : AddressAction

    data class Offer(val port: Int, val path: String) : AddressAction

    data class Say(val message: String) : AddressAction
}

/**
 * [text] typed in the address bar, or a page the tab was asked to open, as what the tab does. A port on the host is the
 * host's own address when this app runs there ([onHost]), else its preview link, else an offer to make one; text that
 * isn't an address says so. Every page a tab loads goes through here, so a `localhost` link from chat opens the
 * preview on a phone.
 */
fun addressAction(text: String, previews: List<ProxyDto>, onHost: Boolean): AddressAction = when (val input = parseAddress(text)) {
    is AddressInput.Go -> AddressAction.Load(input.url)
    is AddressInput.Port -> when (val target = resolvePort(input.port, input.path, previews, onHost)) {
        is PortTarget.Go -> AddressAction.Load(target.url)
        is PortTarget.OfferPreview -> AddressAction.Offer(target.port, target.path)
    }
    is AddressInput.NotAnAddress -> AddressAction.Say("“${input.text}” isn't an address. Type a site, or a port such as 5173.")
}
```

- [ ] **Step 4: Run the tests**

Run: `… ./gradlew :shared:jvmTest --tests 'dev.supermux.browser.*' --max-workers=2`
Expected: PASS: these three files plus the 11 existing browser tests.

- [ ] **Step 5: Commit**

```bash
git add apps/shared/src/commonMain/kotlin/dev/supermux/browser apps/shared/src/commonTest/kotlin/dev/supermux/browser
git commit -m "feat(browser): what a tab writes back and follows, what an address does, the zoom steps (#105)"
```

---

### Task 4: Where a Browser tab opens: the placement the file opener uses, shared

**Files:**
- Create: `apps/shared/src/commonMain/kotlin/dev/supermux/workspace/WorkspaceViewPlacement.kt`
- Create: `apps/shared/src/commonMain/kotlin/dev/supermux/workspace/WorkspaceBrowserOpen.kt`
- Modify: `apps/shared/src/commonMain/kotlin/dev/supermux/workspace/WorkspaceFileOpen.kt` (`place`, `viewIdsOfGroup`)
- Test: `apps/shared/src/commonTest/kotlin/dev/supermux/workspace/WorkspaceBrowserOpenTest.kt`; `WorkspaceFileOpenTest.kt`, unchanged, guards the move.

- [ ] **Step 1: Move the placement mechanics, unchanged.** Create `WorkspaceViewPlacement.kt` with the body of `WorkspaceFileOpener.place` as a function. Its comments come along word for word; only `kind`, the withdrawal hook and the log line are new:

```kotlin
package dev.supermux.workspace

import dev.supermux.proto.ViewDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/**
 * Puts a new view in the layout before the broker has it, then POSTs it (spec §9.0): into [groupId], or with [split]
 * into a new group split from it to the RIGHT. A POST the broker refuses, or answers with another id, takes the tab
 * back out. The file opener's and the Browser tab opener's, so both keep the file opener's hard-won rules (below).
 */
internal fun placeNewView(
    id: String,
    kind: String,
    state: JsonObject,
    groupId: String,
    split: Boolean,
    workspaceId: String,
    provisional: MutableMap<String, ViewDto>,
    edit: ((LayoutNode) -> LayoutNode) -> Unit,
    post: suspend (id: String, state: JsonObject, groupId: String) -> String?,
    scope: CoroutineScope,
    newId: () -> String,
    onPlaced: (viewId: String) -> Unit,
    onWithdrawn: () -> Unit,
) {
    provisional[id] = ViewDto(id = id, workspaceId = workspaceId, kind = kind, state = state)
    if (split) {
        // Add THEN split, the same two tested primitives the "+" menu uses: splitGroup refuses
        // a group with fewer than two views, and an empty group would fail validateLayout, so
        // there is no way to make a fresh group directly. Every intermediate tree stays valid.
        //
        // Minted OUTSIDE the transform: a replay must land on the SAME group id, not invent a
        // new one each time it is rebased onto a frame.
        val newGroupId = newId()
        edit { tree ->
            // Address the view's CURRENT group, never the one it started in. This transform is
            // replayed over every workspace_changed frame, and the split's whole job is to move
            // the view OUT of `groupId` — so a naive `addViewToGroup(tree, groupId, id)` stops
            // finding it there on the second pass and adds it AGAIN. That is what "opening a
            // file opens it twice" was: the same file in two groups at once.
            when (val owner = groupIdOf(tree, id)) {
                // Already split out (our own edit, echoed back). Nothing to do.
                newGroupId -> tree
                // Not placed yet: put it in the source group so splitGroup has two to divide.
                null -> splitGroup(addViewToGroup(tree, groupId, id), groupId, id, "row", newGroupId)
                // The broker placed it (it runs addViewToGroup for the groupId we POSTed).
                // Split it out of wherever it actually landed.
                else -> splitGroup(tree, owner, id, "row", newGroupId)
            }
        }
    } else {
        edit { tree -> addViewToGroup(tree, groupId, id) }
    }
    onPlaced(id)

    scope.launch {
        val created = post(id, state, groupId)
        if (created == id) return@launch
        // Either the POST was rejected (null), or the broker created the view under a DIFFERENT
        // id than the one we drew. Both leave the same wreckage if ignored: a tab naming a view
        // that will never exist, which draws as "view", cannot be closed through the broker, and
        // pins the layout write open forever waiting for a confirmation that cannot come.
        //
        // So both take the same exit — withdraw the optimistic tab and let the broker's own
        // placement stand. Withdrawing is safe to compose: this edit is applied AFTER the split
        // that created the tab, so on every replay the pair cancels out rather than fighting.
        // A mismatched id costs the split (the tab lands wherever the broker put it) but never
        // an unclosable duplicate — degrade, don't wedge.
        if (created != null) {
            println("[placeNewView] broker created the $kind view as $created, not the $id we drew — withdrawing our tab")
        }
        provisional.remove(id)
        onWithdrawn()
        edit { tree -> removeViewFromLayout(tree, id) ?: tree }
    }
}

/** The views held by one group, or empty when the tree has no such group. */
internal fun viewIdsOfGroup(node: LayoutNode, groupId: String): List<String> = when (node) {
    is LayoutNode.Group -> if (node.id == groupId) node.viewIds else emptyList()
    is LayoutNode.Split -> node.children.firstNotNullOfOrNull {
        viewIdsOfGroup(it, groupId).takeIf { ids -> ids.isNotEmpty() }
    } ?: emptyList()
}
```

That is `WorkspaceFileOpener.place`'s text, moved. Then delete `viewIdsOfGroup` from `WorkspaceFileOpen.kt` (it is the same function, now internal) and make `place` call the new function:

```kotlin
    private fun place(
        path: String,
        groupId: String,
        split: Boolean,
        onPlaced: (viewId: String) -> Unit,
    ) {
        val id = newId()
        placing[path] = id
        placeNewView(
            id = id, kind = "editor", state = fileViewState(path), groupId = groupId, split = split,
            workspaceId = workspaceId, provisional = provisional, edit = edit, post = post, scope = scope, newId = newId,
            onPlaced = onPlaced,
            // Two-arg remove(key, value) is a JVM default method, absent on Kotlin/Native.
            onWithdrawn = { if (placing[path] == id) placing.remove(path) },
        )
    }
```

- [ ] **Step 2: The file opener's 18 tests still pass**

Run: `… ./gradlew :shared:jvmTest --tests 'dev.supermux.workspace.WorkspaceFileOpenTest' --max-workers=2`
Expected: PASS, 18 tests.

- [ ] **Step 3: Commit the move on its own**

```bash
git add apps/shared/src/commonMain/kotlin/dev/supermux/workspace
git commit -m "refactor(workspace): the file opener's placement as placeNewView, to share with the Browser tab (#105)"
```

- [ ] **Step 4: Write the failing tests** in `WorkspaceBrowserOpenTest.kt`:

```kotlin
package dev.supermux.workspace

import dev.supermux.proto.ViewDto
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun browserView(id: String, url: String?) = ViewDto(
    id = id, workspaceId = "w1", kind = "browser",
    state = JsonObject(if (url == null) emptyMap() else mapOf("url" to JsonPrimitive(url))),
)

private fun chatView(id: String) = ViewDto(
    id = id, workspaceId = "w1", kind = "chat", state = JsonObject(mapOf("sessionId" to JsonPrimitive("s1"))),
)

@OptIn(ExperimentalCoroutinesApi::class)
class WorkspaceBrowserOpenTest {
    private val chatOnly = LayoutNode.Group("g-chat", listOf("c1"), "c1")
    private val chatAndBrowser = LayoutNode.Split(
        "row", listOf(0.5, 0.5),
        listOf(LayoutNode.Group("g-chat", listOf("c1"), "c1"), LayoutNode.Group("g-web", listOf("b1"), "b1")),
    )
    private val views = mapOf("c1" to chatView("c1"), "b1" to browserView("b1", "https://vitejs.dev/guide/"))

    // ── planBrowserOpen ──────────────────────────────────────────────────────────────

    @Test fun aLinkATabShowsAlreadyBringsThatTabForward() {
        assertEquals(BrowserOpenPlan.Focus("b1", "g-web"), planBrowserOpen(chatAndBrowser, views, "https://vitejs.dev/guide/", sourceViewId = "c1"))
    }

    @Test fun aNewLinkJoinsTheGroupThatHoldsBrowserTabs() {
        assertEquals(BrowserOpenPlan.AddToGroup("g-web"), planBrowserOpen(chatAndBrowser, views, "https://github.com/x", sourceViewId = "c1"))
    }

    @Test fun withNoBrowserTabTheChatsGroupIsSplitToTheRight() {
        assertEquals(BrowserOpenPlan.SplitFrom("g-chat"), planBrowserOpen(chatOnly, mapOf("c1" to chatView("c1")), "https://github.com/x", sourceViewId = "c1"))
    }

    @Test fun aModifiedClickAndMailGoToTheSystem() {
        assertEquals(BrowserOpenPlan.System("https://github.com/x"), planBrowserOpen(chatAndBrowser, views, "https://github.com/x", "c1", modified = true))
        assertEquals(BrowserOpenPlan.System("mailto:a@b.test"), planBrowserOpen(chatAndBrowser, views, "mailto:a@b.test", "c1"))
    }

    @Test fun aLinkThatMustNeverOpenOpensNothing() {
        assertEquals(BrowserOpenPlan.Refused, planBrowserOpen(chatAndBrowser, views, "javascript:alert(1)", "c1"))
    }

    @Test fun anEmptyLayoutTakesTheTabDirectly() {
        assertEquals(BrowserOpenPlan.AddToGroup("g"), planBrowserOpen(LayoutNode.Group("g", emptyList(), null), emptyMap(), "https://x.test/", null))
    }

    // ── WorkspaceBrowserOpener ───────────────────────────────────────────────────────────

    private class Harness(var tree: LayoutNode, views: Map<String, ViewDto>, val postOk: Boolean = true) {
        val provisional = mutableMapOf<String, ViewDto>()
        val serverViews = views.toMutableMap()
        val posted = mutableListOf<Triple<String, JsonObject, String>>()
        val system = mutableListOf<String>()
        var mintCount = 0

        fun opener(scope: kotlinx.coroutines.CoroutineScope) = WorkspaceBrowserOpener(
            workspaceId = "w1",
            treeOf = { tree },
            viewsOf = { provisional + serverViews },
            edit = { transform -> tree = normalizeLayout(transform(tree)) ?: tree },
            provisional = provisional,
            post = { id, state, groupId -> posted += Triple(id, state, groupId); if (postOk) id else null },
            openSystem = { system += it },
            scope = scope,
            newId = { "id${++mintCount}" },
        )
    }

    @Test fun aNewLinkIsATabAtOnceAndAPostOfItsPage() = runTest {
        val h = Harness(chatAndBrowser, views)
        h.opener(this).open("https://github.com/x", sourceViewId = "c1")
        advanceUntilIdle()
        assertEquals("g-web", groupIdOf(h.tree, "id1"))
        assertEquals(Triple("id1", JsonObject(mapOf("url" to JsonPrimitive("https://github.com/x"))), "g-web"), h.posted.single())
    }

    @Test fun theSameLinkTwiceInOneBreathMakesOneTab() = runTest {
        val h = Harness(chatAndBrowser, views)
        val opener = h.opener(this)
        opener.open("https://github.com/x", sourceViewId = "c1")
        opener.open("https://github.com/x", sourceViewId = "c1") // the provisional tab is "a tab that shows it"
        advanceUntilIdle()
        assertEquals(1, h.posted.size)
    }

    @Test fun aRefusedPostTakesTheTabBackOut() = runTest {
        val h = Harness(chatOnly, mapOf("c1" to chatView("c1")), postOk = false)
        h.opener(this).open("https://github.com/x", sourceViewId = "c1")
        advanceUntilIdle()
        assertFalse("id1" in collectViewIds(h.tree))
        assertTrue(h.provisional.isEmpty())
    }

    @Test fun aSystemLinkOpensThereAndPlacesNothing() = runTest {
        val h = Harness(chatAndBrowser, views)
        h.opener(this).open("https://github.com/x", sourceViewId = "c1", modified = true)
        advanceUntilIdle()
        assertEquals(listOf("https://github.com/x"), h.system)
        assertTrue(h.posted.isEmpty())
    }
}
```

- [ ] **Step 5: Run them to see them fail**

Run: `… ./gradlew :shared:jvmTest --tests 'dev.supermux.workspace.WorkspaceBrowserOpenTest' --max-workers=2`
Expected: compilation FAILS: `BrowserOpenPlan`, `planBrowserOpen`, `WorkspaceBrowserOpener` unresolved.

- [ ] **Step 6: Implement** `WorkspaceBrowserOpen.kt`:

```kotlin
// Where a link opens in a workspace (#105): the tab that already shows it, else a new Browser tab beside the others,
// else a new group split to the right of the chat it came from. The same shape as WorkspaceFileOpen.kt, sharing its
// placement (placeNewView).
package dev.supermux.workspace

import dev.supermux.browser.BrowserViewState
import dev.supermux.browser.LinkRoute
import dev.supermux.browser.OpenBrowserTab
import dev.supermux.browser.routeLink
import dev.supermux.proto.ViewDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.JsonObject

/** What opening a link should do. Pure, so the rules are testable on their own. */
sealed interface BrowserOpenPlan {
    /** A tab already shows this very link: bring it forward. */
    data class Focus(val viewId: String, val groupId: String) : BrowserOpenPlan

    /** The workspace has Browser tabs: the new one joins the first group holding one. */
    data class AddToGroup(val groupId: String) : BrowserOpenPlan

    /** No Browser tab yet: split the group the link came from, the new group to the RIGHT. */
    data class SplitFrom(val groupId: String) : BrowserOpenPlan

    /** The system's browser or handler: a modified click, mail, phone, texts. */
    data class System(val url: String) : BrowserOpenPlan

    /** Never opened: javascript:, file:, the app's own scheme, relative links. */
    data object Refused : BrowserOpenPlan

    /** No group to put it in: an empty, unreadable layout. */
    data object Nowhere : BrowserOpenPlan
}

fun ViewDto.isBrowserView(): Boolean = kind == "browser"

/** The first group in document order holding a Browser tab, or null when the workspace has none. */
fun firstGroupWithBrowserView(node: LayoutNode, views: Map<String, ViewDto>): String? = when (node) {
    is LayoutNode.Group -> node.id.takeIf { node.viewIds.any { id -> views[id]?.isBrowserView() == true } }
    is LayoutNode.Split -> node.children.firstNotNullOfOrNull { firstGroupWithBrowserView(it, views) }
}

/**
 * Decide where [url] opens. [sourceViewId] is the view it came from (the chat it was tapped in), used only when there is
 * no Browser tab to join; [modified] is a ⌘/Ctrl-click. [scope] is the window that asked, as for files.
 */
fun planBrowserOpen(
    tree: LayoutNode,
    views: Map<String, ViewDto>,
    url: String,
    sourceViewId: String?,
    modified: Boolean = false,
    scope: LayoutNode = tree,
): BrowserOpenPlan {
    val tabs = views.values.filter { it.isBrowserView() && groupIdOf(scope, it.id) != null }
        .map { OpenBrowserTab(it.id, (BrowserViewState.from(it.state) as? BrowserViewState.Page)?.url) }
    return when (val route = routeLink(url, tabs, modified)) {
        LinkRoute.Refused -> BrowserOpenPlan.Refused
        is LinkRoute.System -> BrowserOpenPlan.System(route.url)
        is LinkRoute.Focus -> BrowserOpenPlan.Focus(route.tabId, groupIdOf(scope, route.tabId)!!)
        is LinkRoute.NewTab -> {
            firstGroupWithBrowserView(scope, views)?.let { return BrowserOpenPlan.AddToGroup(it) }
            val source = sourceViewId?.let { groupIdOf(scope, it) } ?: firstGroupId(scope) ?: return BrowserOpenPlan.Nowhere
            if (viewIdsOfGroup(scope, source).isEmpty()) BrowserOpenPlan.AddToGroup(source) else BrowserOpenPlan.SplitFrom(source)
        }
    }
}

/** Opens links in one workspace: its Browser tabs, or the system's browser. Read the layout and views at call time. */
class WorkspaceBrowserOpener(
    private val workspaceId: String,
    private val treeOf: () -> LayoutNode,
    /** Every view the workspace has, provisional ones included, so a second click finds the first one's tab. */
    private val viewsOf: () -> Map<String, ViewDto>,
    private val edit: ((LayoutNode) -> LayoutNode) -> Unit,
    private val provisional: MutableMap<String, ViewDto>,
    /** POST a `browser` view with the id we drew: the id the broker created, null when it refused. */
    private val post: suspend (id: String, state: JsonObject, groupId: String) -> String?,
    private val openSystem: (url: String) -> Unit,
    private val scope: CoroutineScope,
    private val newId: () -> String,
) {
    fun open(url: String, sourceViewId: String? = null, modified: Boolean = false, onPlaced: (viewId: String) -> Unit = {}) {
        when (val plan = planBrowserOpen(treeOf(), viewsOf(), url, sourceViewId, modified)) {
            is BrowserOpenPlan.Focus -> {
                edit { setActiveViewInGroup(it, plan.groupId, plan.viewId) }
                onPlaced(plan.viewId)
            }
            is BrowserOpenPlan.AddToGroup -> place(url, plan.groupId, split = false, onPlaced)
            is BrowserOpenPlan.SplitFrom -> place(url, plan.groupId, split = true, onPlaced)
            is BrowserOpenPlan.System -> openSystem(plan.url)
            BrowserOpenPlan.Refused -> Unit
            BrowserOpenPlan.Nowhere -> println("[WorkspaceBrowserOpener] no group to open a link into — layout is empty")
        }
    }

    private fun place(url: String, groupId: String, split: Boolean, onPlaced: (viewId: String) -> Unit) {
        // A link the broker would refuse as a tab's page (over 4096 characters) still opens, in the system's browser.
        val state = BrowserViewState.page(url)?.toJson() ?: return openSystem(url)
        placeNewView(
            id = newId(), kind = "browser", state = state, groupId = groupId, split = split, workspaceId = workspaceId,
            provisional = provisional, edit = edit, post = post, scope = scope, newId = newId,
            onPlaced = onPlaced, onWithdrawn = {},
        )
    }
}
```

- [ ] **Step 7: Run the workspace tests**

Run: `… ./gradlew :shared:jvmTest --tests 'dev.supermux.workspace.*' --max-workers=2`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add apps/shared/src/commonMain/kotlin/dev/supermux/workspace apps/shared/src/commonTest/kotlin/dev/supermux/workspace
git commit -m "feat(workspace): where a link opens: its tab, the Browser tabs' group, or a split right of the chat (#105)"
```

---

### Task 5: The broker seams, `BrowserActions`, and the locals the tab reads

**Files:**
- Modify: `apps/shared/src/commonMain/kotlin/dev/supermux/util/TransportPolicy.kt` (add `isLoopback`)
- Modify: `apps/shared/src/commonMain/kotlin/dev/supermux/net/BrokerApi.kt` (DTOs next to `CreateProxyResponse` at :593; `previewToken()` after `createProxy` at :2903)
- Create: `apps/ui/src/commonMain/kotlin/dev/supermux/ui/browser/BrowserActions.kt`
- Create: `apps/ui/src/commonMain/kotlin/dev/supermux/ui/browser/AppWebViews.kt`
- Create: `apps/ui/src/commonMain/kotlin/dev/supermux/ui/browser/LinkOpener.kt`
- Modify: `apps/ui/src/commonMain/kotlin/dev/supermux/ui/shell/ShellActions.kt` (a `browser` member; the `HostStore` builder at :165)
- Test: `apps/shared/src/commonTest/kotlin/dev/supermux/util/TransportPolicyTest.kt`, `apps/shared/src/commonTest/kotlin/dev/supermux/net/BrokerApiPreviewTokenTest.kt` (new), `apps/ui/src/jvmTest/kotlin/dev/supermux/ui/browser/BrowserActionsTest.kt` (new)

- [ ] **Step 1: Write the failing tests.** Append to `TransportPolicyTest`:

```kotlin
    @Test fun aBrokerOnLoopbackRunsOnThisMachine() {
        assertTrue(TransportPolicy.isLoopback("http://127.0.0.1:9898"))
        assertTrue(TransportPolicy.isLoopback("http://localhost:9898/"))
        assertTrue(TransportPolicy.isLoopback("http://[::1]:9898"))
        assertFalse(TransportPolicy.isLoopback("https://h-x.relay.supermux.dev"))
        assertFalse(TransportPolicy.isLoopback("http://100.121.185.86:9898"))
    }
```

`BrokerApiPreviewTokenTest.kt`:

```kotlin
package dev.supermux.net

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** #105 car 2's route, as the app reads it: a token and the cookie to keep it in, asked for with the device's bearer. */
class BrokerApiPreviewTokenTest {
    @Test fun aPreviewTokenIsAskedForWithTheBearerAndItsCookieDecoded() = runTest {
        val seen = mutableListOf<HttpRequestData>()
        val body = """{"token":"pv_abc","cookie":{"name":"cmux_preview","value":"pv_abc","url":"https://h.test/p/",""" +
            """"path":"/p/","httpOnly":true,"secure":true,"sameSite":"Lax","maxAge":31536000}}"""
        val api = BrokerApi("http://h", "tok", HttpClient(MockEngine { req ->
            seen += req
            respond(content = ByteReadChannel(body), headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }))
        val answer = api.previewToken()
        assertEquals(HttpMethod.Post, seen.single().method)
        assertEquals("/devices/preview-token", seen.single().url.encodedPath)
        assertEquals("Bearer tok", seen.single().headers[HttpHeaders.Authorization])
        assertEquals(PreviewCookieDto("cmux_preview", "pv_abc", "https://h.test/p/", path = "/p/", maxAge = 31536000), answer.cookie)
    }
}
```

`apps/ui/src/jvmTest/kotlin/dev/supermux/ui/browser/BrowserActionsTest.kt`:

```kotlin
package dev.supermux.ui.browser

import dev.supermux.proto.SessionInfo
import dev.supermux.proto.ViewDto
import dev.supermux.proto.WorkspaceDto
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BrowserActionsTest {
    private fun chat(id: String, session: String) =
        ViewDto(id = id, workspaceId = "w", kind = "chat", state = JsonObject(mapOf("sessionId" to JsonPrimitive(session))))

    private val sessions = listOf(
        SessionInfo(id = "s1", name = "first", workdir = "/w", agent = "claude"),
        SessionInfo(id = "s2", name = "second", workdir = "/w", agent = "claude"),
    )

    @Test fun aPrivateLinkIsMadeForTheWorkspacesPrimarySessionElseItsLastChat() {
        val views = listOf(chat("c1", "s1"), ViewDto(id = "t", workspaceId = "w", kind = "terminal"), chat("c2", "s2"))
        assertEquals("first", previewSessionName(WorkspaceDto(id = "w", name = "w", workdir = "/w", primarySessionId = "s1", views = views), sessions))
        assertEquals("second", previewSessionName(WorkspaceDto(id = "w", name = "w", workdir = "/w", views = views), sessions))
        assertNull(previewSessionName(WorkspaceDto(id = "w", name = "w", workdir = "/w"), sessions), "no chat, no session")
        assertNull(previewSessionName(null, sessions))
    }
}
```

- [ ] **Step 2: Run them to see them fail**

Run: `… ./gradlew :shared:jvmTest --tests 'dev.supermux.util.TransportPolicyTest' --tests 'dev.supermux.net.BrokerApiPreviewTokenTest' :ui:jvmTest --tests 'dev.supermux.ui.browser.BrowserActionsTest' --max-workers=2`
Expected: compilation FAILS: `isLoopback`, `previewToken`, `PreviewCookieDto`, `previewSessionName` unresolved.

- [ ] **Step 3: The shared halves.** In `TransportPolicy`, after `isPlainHttpAllowedWithoutOptIn`:

```kotlin
    /** True when [url]'s host is this machine (`localhost`, `127.0.0.0/8`, `::1`): a broker reached there runs here. */
    fun isLoopback(url: String): Boolean = isLoopbackHost(hostOf(url))
```

In `BrokerApi.kt`, after `CreateProxyResponse`:

```kotlin
/** The cookie the broker asks a Browser tab to keep (#105 car 2): its preview-only token, for this host's previews. */
@Serializable
data class PreviewCookieDto(
    val name: String,
    val value: String,
    val url: String,
    val path: String = "/",
    val domain: String? = null,
    val httpOnly: Boolean = true,
    val secure: Boolean = true,
    val sameSite: String = "Lax",
    val maxAge: Long? = null,
)

/** POST /devices/preview-token's answer. */
@Serializable
data class PreviewTokenResponse(val token: String, val cookie: PreviewCookieDto)
```

and after `createProxy`:

```kotlin
    /** POST /devices/preview-token: a token that opens this host's port previews and nothing else (#105). Bearer only. */
    suspend fun previewToken(): PreviewTokenResponse =
        decode(http.post("$httpBase/devices/preview-token") { authHeader() })
```

- [ ] **Step 4: `BrowserActions.kt`**

```kotlin
package dev.supermux.ui.browser

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import dev.supermux.net.PatchViewBody
import dev.supermux.net.PreviewCookieDto
import dev.supermux.net.ProxyDto
import dev.supermux.proto.SessionInfo
import dev.supermux.proto.WorkspaceDto
import dev.supermux.proto.chatSessionId
import dev.supermux.state.HostStore
import dev.supermux.util.TransportPolicy
import kotlinx.serialization.json.JsonObject

/**
 * The broker calls a Browser tab makes (#105), in one holder like DisplayActions. Defaults are the empty answers, so a
 * test or a preview can host the pane without a broker.
 */
@Immutable
class BrowserActions(
    /** Write the tab's page (its `{url}` state) and title to its view. False when the broker refused. */
    val saveView: suspend (workspaceId: String, viewId: String, state: JsonObject, title: String?) -> Boolean =
        { _, _, _, _ -> false },
    /** The host's port previews: the bar names a preview by its port, and a port typed opens its preview. */
    val proxies: suspend () -> List<ProxyDto> = { emptyList() },
    /** Expose [port] privately (paired devices only) for the workspace's session: the new preview, or null. */
    val makePreview: suspend (workspaceId: String, port: Int) -> ProxyDto? = { _, _ -> null },
    /** This device's preview-only cookie for the host, asked for once a run; null when the host gives none. */
    val previewCookie: suspend () -> PreviewCookieDto? = { null },
    /** Whether this app runs on the host, so a `localhost` address is the host's own. */
    val onHost: Boolean = false,
)

/** [BrowserActions] against one paired host. */
@Composable
fun rememberBrowserActions(app: HostStore): BrowserActions = remember(app) {
    var asked = false
    var cookie: PreviewCookieDto? = null
    BrowserActions(
        saveView = { workspaceId, viewId, state, title ->
            runCatching { app.api.patchView(workspaceId, viewId, PatchViewBody(title = title, state = state)) }.isSuccess
        },
        proxies = { app.proxies() },
        makePreview = { workspaceId, port ->
            val name = previewSessionName(app.workspaces.value.firstOrNull { it.id == workspaceId }, app.sessions.value)
            name?.let { runCatching { app.api.createProxy(it, port) }.getOrNull() }?.let { made ->
                // POST /proxies makes a private preview (registry: isPublic defaults to false). Never a public one here.
                ProxyDto(domain = made.domain, sessionName = name, port = made.port, url = made.url.ifEmpty { null })
            }
        },
        // One token a run: the broker mints a new one each time it's asked. The web client, signed in by the app's own
        // cookie, is refused (the route takes a bearer only), and its iframe tab needs none.
        previewCookie = {
            if (!asked) {
                asked = true
                cookie = runCatching { app.api.previewToken().cookie }.getOrNull()
            }
            cookie
        },
        // A broker reached over loopback runs on this machine: a desktop that is the host reaches its own that way.
        onHost = TransportPolicy.isLoopback(app.baseUrl),
    )
}

/** The session a private link is made for: the workspace's primary session, else its last chat's (views come in order). */
internal fun previewSessionName(workspace: WorkspaceDto?, sessions: List<SessionInfo>): String? {
    val id = workspace?.primarySessionId ?: workspace?.views?.lastOrNull { it.chatSessionId() != null }?.chatSessionId()
    return id?.let { sid -> sessions.firstOrNull { it.id == sid }?.name }
}
```

- [ ] **Step 5: `AppWebViews.kt` and `LinkOpener.kt`.** Tasks 7 and 8 test what these do.

```kotlin
package dev.supermux.ui.browser

import androidx.compose.runtime.staticCompositionLocalOf
import dev.supermux.webview.RuntimeConfig
import dev.supermux.webview.WebProfile
import dev.supermux.webview.WebViewRuntime

/**
 * The app's web views (#105): one runtime for the process, made the first time a Browser tab shows a page, so an app
 * that never opens one loads no web engine. Tests provide their own, on the fake engine, through [LocalAppWebViews].
 */
class AppWebViews(val runtime: WebViewRuntime) {
    /** The hosts (by the cookie's URL) whose preview cookie this run has put in [BROWSER_PROFILE]. */
    internal val previewCookies: MutableSet<String> = mutableSetOf()

    companion object {
        val app: AppWebViews by lazy { AppWebViews(WebViewRuntime(RuntimeConfig.platformDefault().copy(maxLive = 4))) }
    }
}

/** The web views a Browser pane uses: the app's, unless a test provides its own. */
val LocalAppWebViews = staticCompositionLocalOf<AppWebViews?> { null }

/** The Browser tabs' own profile: their cookies and logins, kept apart from every other web view of the app's. */
val BROWSER_PROFILE: WebProfile = WebProfile.Named("supermux-browser")
```

```kotlin
package dev.supermux.ui.browser

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput

/** Opens a link where the workspace says (#105): the tab that shows it, a new Browser tab, or the system's browser. */
fun interface LinkOpener {
    fun open(url: String, sourceViewId: String?, modified: Boolean)
}

/** The workspace's opener. Null outside a workspace, and on the web client, whose links open a tab of the browser itself. */
val LocalLinkOpener = staticCompositionLocalOf<LinkOpener?> { null }

/** What a chat's links call: its view's opener, with the click's modifiers. Null → `Platform.openUrl`. */
val LocalOpenLink = staticCompositionLocalOf<((String) -> Unit)?> { null }

/** The ⌘/Ctrl state of the last press inside, for a link click, which carries no modifiers of its own. */
class ClickModifiers {
    var modified: Boolean = false
        internal set
}

/** Records [into] each press's ⌘/Ctrl state before anything inside handles the press (the Initial pass). */
fun Modifier.trackClickModifiers(into: ClickModifiers): Modifier = pointerInput(into) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.type == PointerEventType.Press) {
                into.modified = event.keyboardModifiers.isMetaPressed || event.keyboardModifiers.isCtrlPressed
            }
        }
    }
}
```

- [ ] **Step 6: `ShellActions` carries them.** In `class ShellActions`, after `val display: DisplayActions = DisplayActions(),`:

```kotlin
    /** The Browser tab's seams (#105): its view writes, the host's previews, its preview cookie. */
    val browser: dev.supermux.ui.browser.BrowserActions = dev.supermux.ui.browser.BrowserActions(),
```

In the `HostStore` builder: add `val browserActions = dev.supermux.ui.browser.rememberBrowserActions(app)` after `val displayActions = rememberDisplayActions(app)`, add `browserActions` to `remember(app, walkthroughCap, displayActions)`'s keys, and `browser = browserActions,` after `display = displayActions,`. The fleet builder keeps the default: the workspace panes build theirs from the `HostStore` that owns the workspace (`WorkspacePanes.kt:1124`, `SupermuxApp.kt:1915`).

- [ ] **Step 7: Run the tests**

Run: the Step 2 command.
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add apps/shared apps/ui
git commit -m "feat(browser): the tab's broker seams (view writes, previews, a private link, the preview cookie) and locals (#105)"
```

---

### Task 6: The tab's bars: the toolbar, the find bar, the offer page, the messages, the shortcuts

**Files:**
- Create: `apps/ui/src/commonMain/kotlin/dev/supermux/ui/browser/BrowserBars.kt`
- Test: `apps/ui/src/jvmTest/kotlin/dev/supermux/ui/browser/BrowserBarsTest.kt`

- [ ] **Step 1: Write the failing tests**

```kotlin
package dev.supermux.ui.browser

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.unit.dp
import dev.supermux.browser.DisplayAddress
import dev.supermux.ui.chat.setPlatformContent
import dev.supermux.webview.FindResult
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class BrowserBarsTest {
    @Test fun aPreviewShowsAsItsPortAndTheSchemeNever() {
        assertEquals("port 5173/login", DisplayAddress.Preview(5173, "/login").text())
        assertEquals("localhost:3000", DisplayAddress.Local(3000, "/").text())
        assertEquals("vitejs.dev/guide/", DisplayAddress.Web("vitejs.dev", "/guide/").text())
    }

    @Test fun findSaysWhatTheEngineFound() {
        assertEquals("2 of 5", findText("x", FindResult(found = true, count = 5, current = 2)))
        assertEquals("No matches", findText("x", FindResult(found = false)))
        assertEquals("Find isn't available here yet", findText("x", null))
        assertEquals("", findText("", null))
    }

    @Test fun theAddressShowsThePortAndEditsTheWholeUrl() = runComposeUiTest {
        var submitted: String? = null
        setPlatformContent {
            BrowserToolbar(
                shown = "port 5173/login", full = "https://relay.test/p/p-5173/login", canGoBack = false, canGoForward = false,
                loading = false, zoomPercent = 100, address = remember { FocusRequester() }, onBack = {}, onForward = {}, onReload = {},
                onSubmit = { submitted = it }, onOpenExternally = {}, onFind = {}, onZoom = {}, onInspect = {},
            )
        }
        onNodeWithTag("browser-address").assertTextEquals("port 5173/login")
        onNodeWithTag("browser-address").performClick()
        onNodeWithTag("browser-address").assertTextEquals("https://relay.test/p/p-5173/login")
        onNodeWithTag("browser-address").performTextReplacement("vitejs.dev")
        onNodeWithTag("browser-address").performKeyInput { pressKey(Key.Enter) }
        assertEquals("vitejs.dev", submitted)
    }

    @Test fun theOfferAsksOnceAndSaysWhenTheHostRefused() = runComposeUiTest {
        var asks = 0
        var answer: ((Boolean) -> Unit)? = null
        setPlatformContent { PortOffer(5173, onMake = { done -> asks++; answer = done }) }
        onNodeWithText("Port 5173 runs on the host, not on this device.").assertExists()
        onNodeWithTag("browser-make-link").performClick()
        onNodeWithTag("browser-make-link").assertIsNotEnabled()
        answer!!(false)
        waitForIdle()
        onNodeWithText("The host didn't make one", substring = true).assertExists()
        assertEquals(1, asks)
    }

    @Test fun cmdOrCtrlWithTheirKeysAreTheTabsShortcutsAndPlainKeysAreNot() = runComposeUiTest {
        val seen = mutableListOf<BrowserShortcut>()
        setPlatformContent {
            Box(Modifier.size(100.dp).testTag("k").focusable().onPreviewKeyEvent { e -> browserShortcut(e)?.let { seen += it; true } ?: false })
        }
        onNodeWithTag("k").requestFocus()
        onNodeWithTag("k").performKeyInput {
            withKeyDown(Key.CtrlLeft) { pressKey(Key.R) }
            withKeyDown(Key.MetaLeft) { pressKey(Key.L) }
            pressKey(Key.R)
        }
        assertEquals(listOf(BrowserShortcut.Reload, BrowserShortcut.FocusAddress), seen)
    }
}
```

- [ ] **Step 2: Run them to see them fail**

Run: `… ./gradlew :ui:jvmTest --tests 'dev.supermux.ui.browser.BrowserBarsTest' --max-workers=2`
Expected: compilation FAILS: `text`, `findText`, `BrowserToolbar`, `PortOffer`, `browserShortcut` unresolved.

- [ ] **Step 3: Implement `BrowserBars.kt`**

```kotlin
// A Browser tab's bars (#105): what sits over and in place of its page. Plain composables over plain values, so each is
// tested on its own; BrowserPane wires them to the page.
package dev.supermux.ui.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.supermux.browser.DisplayAddress
import dev.supermux.webview.FindResult
import kotlinx.coroutines.launch

/** How the address bar shows a page: a preview as its port, never the scheme. */
internal fun DisplayAddress.text(): String = when (this) {
    is DisplayAddress.Preview -> "port $port" + path.takeIf { it != "/" }.orEmpty()
    is DisplayAddress.Local -> "localhost:$port" + path.takeIf { it != "/" }.orEmpty()
    is DisplayAddress.Web -> host + path.takeIf { it != "/" }.orEmpty()
}

/** The pane's own shortcuts until #96's registry is on dev: ⌘ or Ctrl with L, R, F, [, ], =, −, 0. */
internal enum class BrowserShortcut { FocusAddress, Reload, Find, Back, Forward, ZoomIn, ZoomOut, ZoomReset }

internal fun browserShortcut(event: KeyEvent): BrowserShortcut? {
    if (event.type != KeyEventType.KeyDown || !(event.isMetaPressed || event.isCtrlPressed)) return null
    return when (event.key) {
        Key.L -> BrowserShortcut.FocusAddress
        Key.R -> BrowserShortcut.Reload
        Key.F -> BrowserShortcut.Find
        Key.LeftBracket -> BrowserShortcut.Back
        Key.RightBracket -> BrowserShortcut.Forward
        Key.Equals, Key.Plus, Key.NumPadAdd -> BrowserShortcut.ZoomIn
        Key.Minus, Key.NumPadSubtract -> BrowserShortcut.ZoomOut
        Key.Zero, Key.NumPad0 -> BrowserShortcut.ZoomReset
        else -> null
    }
}

/**
 * The bar over a page: back, forward, reload (stop while it loads), the address and a menu. The address shows [shown]
 * (a preview as its port) and, while it has the focus, all of [full] to edit. Enter submits; Escape gives up.
 */
@Composable
internal fun BrowserToolbar(
    shown: String,
    full: String?,
    canGoBack: Boolean,
    canGoForward: Boolean,
    loading: Boolean,
    zoomPercent: Int,
    address: FocusRequester,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onReload: () -> Unit,
    onSubmit: (String) -> Unit,
    onOpenExternally: () -> Unit,
    onFind: () -> Unit,
    onZoom: (direction: Int) -> Unit,
    onInspect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val focus = LocalFocusManager.current
    var editing by remember { mutableStateOf(false) }
    var field by remember { mutableStateOf(TextFieldValue(shown)) }
    LaunchedEffect(shown, editing) { if (!editing) field = TextFieldValue(shown) }
    var menu by remember { mutableStateOf(false) }
    fun submit() {
        onSubmit(field.text)
        editing = false
        focus.clearFocus()
    }
    Row(modifier.fillMaxWidth().height(40.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack, enabled = canGoBack, modifier = Modifier.testTag("browser-back")) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
        }
        IconButton(onClick = onForward, enabled = canGoForward, modifier = Modifier.testTag("browser-forward")) {
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Forward")
        }
        IconButton(onClick = onReload, modifier = Modifier.testTag("browser-reload")) {
            Icon(if (loading) Icons.Filled.Close else Icons.Filled.Refresh, contentDescription = if (loading) "Stop" else "Reload")
        }
        BasicTextField(
            value = field,
            onValueChange = { field = it },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = cs.onSurface),
            cursorBrush = SolidColor(cs.primary),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { submit() }),
            modifier = Modifier.weight(1f).focusRequester(address).testTag("browser-address")
                .onFocusChanged { f ->
                    if (f.isFocused && !editing) {
                        editing = true
                        val text = full.orEmpty()
                        field = TextFieldValue(text, TextRange(0, text.length))
                    } else if (!f.isFocused) {
                        editing = false
                    }
                }
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (e.key) {
                        Key.Enter, Key.NumPadEnter -> { submit(); true }
                        Key.Escape -> { editing = false; field = TextFieldValue(shown); focus.clearFocus(); true }
                        else -> false
                    }
                },
            decorationBox = { inner ->
                Box(Modifier.fillMaxWidth().background(cs.surfaceContainerHigh, RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 6.dp)) {
                    if (field.text.isEmpty()) Text("Type a site, or a port such as 5173", color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                    inner()
                }
            },
        )
        Box {
            IconButton(onClick = { menu = true }, modifier = Modifier.testTag("browser-menu")) {
                Icon(Icons.Filled.MoreVert, contentDescription = "More")
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Find in page") }, onClick = { menu = false; onFind() }, modifier = Modifier.testTag("browser-menu-find"))
                DropdownMenuItem(text = { Text("Zoom in") }, onClick = { onZoom(+1) })
                DropdownMenuItem(text = { Text("Zoom out") }, onClick = { onZoom(-1) })
                DropdownMenuItem(text = { Text("Actual size ($zoomPercent%)") }, onClick = { onZoom(0) })
                DropdownMenuItem(text = { Text("Inspect") }, onClick = { menu = false; onInspect() })
                DropdownMenuItem(text = { Text("Open in your browser") }, onClick = { menu = false; onOpenExternally() }, modifier = Modifier.testTag("browser-menu-external"))
            }
        }
    }
}

/** What the find bar says about an answer; a null answer is an engine that can't find yet (plans 2b–2d). */
internal fun findText(query: String, result: FindResult?): String = when {
    query.isEmpty() -> ""
    result == null -> "Find isn't available here yet"
    !result.found -> "No matches"
    result.current != null && result.count != null -> "${result.current} of ${result.count}"
    result.count != null -> "${result.count} matches"
    else -> "Found"
}

/** Find in page: the query, what the engine found, previous, next and close. Enter is next, Shift+Enter previous. */
@Composable
internal fun FindBar(onFind: suspend (query: String, backwards: Boolean) -> FindResult?, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    var query by remember { mutableStateOf("") }
    var said by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    fun run(backwards: Boolean) {
        val q = query
        scope.launch { said = findText(q, if (q.isEmpty()) null else onFind(q, backwards)) }
    }
    Row(modifier.fillMaxWidth().height(36.dp).padding(horizontal = 8.dp).testTag("browser-find"), verticalAlignment = Alignment.CenterVertically) {
        BasicTextField(
            value = query,
            onValueChange = { query = it; run(false) },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = cs.onSurface),
            cursorBrush = SolidColor(cs.primary),
            modifier = Modifier.weight(1f).focusRequester(focus).testTag("browser-find-field").onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.Enter, Key.NumPadEnter -> { run(backwards = e.isShiftPressed); true }
                    Key.Escape -> { onClose(); true }
                    else -> false
                }
            },
        )
        Text(said, color = cs.onSurfaceVariant, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 8.dp).testTag("browser-find-count"))
        IconButton(onClick = { run(true) }) { Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Previous") }
        IconButton(onClick = { run(false) }) { Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Next") }
        IconButton(onClick = onClose, modifier = Modifier.testTag("browser-find-close")) { Icon(Icons.Filled.Close, contentDescription = "Close find") }
    }
}

/** A port on the host with no preview, on a device that isn't the host: the offer from the proposal. Never automatic. */
@Composable
internal fun PortOffer(port: Int, onMake: (done: (ok: Boolean) -> Unit) -> Unit, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    var making by remember(port) { mutableStateOf(false) }
    var failed by remember(port) { mutableStateOf(false) }
    Column(modifier.fillMaxSize().padding(24.dp).testTag("browser-offer"), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Port $port runs on the host, not on this device.", style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = { making = true; failed = false; onMake { ok -> making = false; failed = !ok } },
            enabled = !making,
            modifier = Modifier.testTag("browser-make-link"),
        ) { Text(if (making) "Making a private link…" else "Make a private link") }
        Spacer(Modifier.height(8.dp))
        Text(
            if (failed) "The host didn't make one. Is the workspace's session still running?" else "Only your paired devices can open it.",
            color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
        )
    }
}

/** A message in place of the page (a new tab, text that isn't an address, no engine), with an action when there's one. */
@Composable
internal fun BrowserMessage(text: String, tag: String, action: Pair<String, () -> Unit>? = null) {
    Column(Modifier.fillMaxSize().padding(24.dp).testTag(tag), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        action?.let { (label, run) -> TextButton(onClick = run) { Text(label) } }
    }
}
```

- [ ] **Step 4: Run the tests**

Run: the Step 2 command.
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add apps/ui/src/commonMain/kotlin/dev/supermux/ui/browser/BrowserBars.kt apps/ui/src/jvmTest/kotlin/dev/supermux/ui/browser/BrowserBarsTest.kt
git commit -m "feat(browser): the tab's toolbar, find bar, port offer and messages, and its own shortcuts (#105)"
```

---

### Task 7: The pane: a page, synced, under its bars; `ViewHost` draws it

**Files:**
- Create: `apps/ui/src/commonMain/kotlin/dev/supermux/ui/browser/BrowserPane.kt`
- Modify: `apps/ui/src/commonMain/kotlin/dev/supermux/ui/shell/ViewHost.kt:315-321` (a `"browser"` branch before `else`)
- Test: `apps/ui/src/jvmTest/kotlin/dev/supermux/ui/browser/BrowserPaneTest.kt`; `apps/ui/src/jvmTest/kotlin/dev/supermux/ui/shell/ViewHostTest.kt` (one test)

- [ ] **Step 1: Write the failing tests.** `BrowserPaneTest.kt` drives the pane on the library's fake engine. An engine records each call: `load:` with the content's key (`url:<url>` for a page), `reload`, `find:<query>:<backwards>`, `zoom:<factor>` and `cookie:<name>`.

```kotlin
package dev.supermux.ui.browser

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.unit.dp
import dev.supermux.net.PreviewCookieDto
import dev.supermux.net.ProxyDto
import dev.supermux.proto.ViewDto
import dev.supermux.ui.chat.setPlatformContent
import dev.supermux.ui.platform.FakePlatform
import dev.supermux.webview.FindResult
import dev.supermux.webview.RuntimeConfig
import dev.supermux.webview.UnavailableReason
import dev.supermux.webview.WebViewRuntime
import dev.supermux.webview.testing.FakeEngineBehavior
import dev.supermux.webview.testing.FakeWebEngineFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class BrowserPaneTest {
    private fun view(url: String?) = ViewDto(
        id = "b1", workspaceId = "w1", kind = "browser",
        state = JsonObject(if (url == null) emptyMap() else mapOf("url" to JsonPrimitive(url))),
    )

    private val preview = ProxyDto(domain = "p-5173", sessionName = "s", port = 5173, url = "https://relay.test/p/p-5173/")
    private val cookie = PreviewCookieDto(name = "cmux_preview", value = "pv", url = "https://relay.test/p/", path = "/p/")

    private class Rig {
        val engines = FakeWebEngineFactory(behavior = FakeEngineBehavior())
        val writes = mutableListOf<Pair<JsonObject, String?>>()
        var made = 0
        val calls: List<String> get() = engines.created.flatMap { it.calls }
        fun loads(): List<String> = calls.filter { it.startsWith("load:") && "url:" in it }.map { it.substringAfter("url:") }
    }

    private fun Rig.actions(previews: List<ProxyDto> = emptyList(), cookie: PreviewCookieDto? = null) = BrowserActions(
        saveView = { _, _, state, title -> writes += state to title; true },
        proxies = { previews },
        makePreview = { _, port -> made++; preview.copy(port = port) },
        previewCookie = { cookie },
    )

    private fun ComposeUiTest.show(rig: Rig, view: MutableState<ViewDto>, actions: BrowserActions, platform: FakePlatform = FakePlatform(), opener: LinkOpener? = null) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val runtime = WebViewRuntime(
            RuntimeConfig(maxLive = 2, snapshotCacheBytes = 8L shl 20, factory = rig.engines, scope = scope, clock = { mainClock.currentTime }),
        )
        setPlatformContent(platform = platform) {
            CompositionLocalProvider(LocalAppWebViews provides AppWebViews(runtime), LocalLinkOpener provides opener) {
                BrowserPane(view.value, "w1", actions, Modifier.size(800.dp, 600.dp))
            }
        }
    }

    @Test fun theStoredPageOpensAndIsWrittenBack() = runComposeUiTest {
        val rig = Rig()
        show(rig, mutableStateOf(view("https://vitejs.dev/")), rig.actions())
        waitUntil(timeoutMillis = 5_000) { "https://vitejs.dev/" in rig.loads() }
        waitUntil(timeoutMillis = 5_000) { rig.writes.isNotEmpty() }
        assertEquals(JsonObject(mapOf("url" to JsonPrimitive("https://vitejs.dev/"))), rig.writes.last().first)
        onNodeWithTag("browser-address").assertTextEquals("vitejs.dev")
    }

    @Test fun aNewTabWaitsForAnAddressAndLoadsWhatIsTyped() = runComposeUiTest {
        val rig = Rig()
        show(rig, mutableStateOf(view(null)), rig.actions())
        onNodeWithTag("browser-new-tab").assertExists()
        assertTrue(rig.engines.created.isEmpty(), "a new tab makes no engine")
        onNodeWithTag("browser-address").performClick()
        onNodeWithTag("browser-address").performTextReplacement("vitejs.dev")
        onNodeWithTag("browser-address").performKeyInput { pressKey(Key.Enter) }
        waitUntil(timeoutMillis = 5_000) { "https://vitejs.dev" in rig.loads() }
    }

    @Test fun aPageAnotherClientStoredIsFollowed() = runComposeUiTest {
        val rig = Rig()
        val v = mutableStateOf(view("https://a.test/"))
        show(rig, v, rig.actions())
        waitUntil(timeoutMillis = 5_000) { "https://a.test/" in rig.loads() }
        v.value = view("https://c.test/")
        waitUntil(timeoutMillis = 5_000) { "https://c.test/" in rig.loads() }
    }

    @Test fun aLocalhostLinkOnAnotherDeviceOpensThePortsPreview() = runComposeUiTest {
        val rig = Rig()
        show(rig, mutableStateOf(view("http://localhost:5173/login")), rig.actions(previews = listOf(preview)))
        waitUntil(timeoutMillis = 5_000) { "https://relay.test/p/p-5173/login" in rig.loads() }
    }

    @Test fun aPortWithNoPreviewIsOfferedAndTheLinkMadeLoadsIt() = runComposeUiTest {
        val rig = Rig()
        show(rig, mutableStateOf(view("http://localhost:5173/")), rig.actions())
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("browser-offer").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("browser-make-link").performClick()
        waitUntil(timeoutMillis = 5_000) { rig.loads().any { it.startsWith("https://relay.test/p/p-5173/") } }
        assertEquals(1, rig.made)
    }

    @Test fun aPreviewTakesTheCookieBeforeItLoads() = runComposeUiTest {
        val rig = Rig()
        show(rig, mutableStateOf(view("https://relay.test/p/p-5173/")), rig.actions(previews = listOf(preview), cookie = cookie))
        waitUntil(timeoutMillis = 5_000) { "https://relay.test/p/p-5173/" in rig.loads() }
        val cookieAt = rig.calls.indexOf("cookie:cmux_preview")
        val loadAt = rig.calls.indexOfFirst { it.startsWith("load:") && it.endsWith("https://relay.test/p/p-5173/") }
        assertTrue(cookieAt in 0 until loadAt, "the cookie first: ${rig.calls}")
    }

    @Test fun ctrlRReloadsAndCtrlFFindsWithTheEnginesCount() = runComposeUiTest {
        val rig = Rig()
        show(rig, mutableStateOf(view("https://vitejs.dev/")), rig.actions())
        waitUntil(timeoutMillis = 5_000) { "https://vitejs.dev/" in rig.loads() }
        rig.engines.created.forEach { it.findAnswer = { _, _ -> FindResult(found = true, count = 5, current = 2) } }
        onNodeWithTag("browser-address").performClick()
        onNodeWithTag("browser-address").performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.R) } }
        waitUntil(timeoutMillis = 5_000) { "reload" in rig.calls }
        onNodeWithTag("browser-address").performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.F) } }
        onNodeWithTag("browser-find-field").performTextInput("vite")
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("browser-find-count").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("browser-find-count").assertTextEquals("2 of 5")
    }

    @Test fun noEngineHereSaysSoAndOffersTheSystemBrowser() = runComposeUiTest {
        val rig = Rig().apply { engines.unavailable = UnavailableReason.NotImplemented }
        val platform = FakePlatform()
        show(rig, mutableStateOf(view("https://vitejs.dev/")), rig.actions(), platform)
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("browser-unavailable").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Open in your browser").performClick()
        assertEquals(listOf("https://vitejs.dev/"), platform.openedUrls)
    }
}
```

Append to `ViewHostTest`:

```kotlin
    @Test fun aBrowserViewDrawsTheBrowserPaneNotTheUnknownHint() = runComposeUiTest {
        setPlatformContent { ViewHost(view("browser", emptyMap()), "w1", "/w", ShellActions(), mutableStateMapOf()) }
        onNodeWithTag("browser-v1").assertExists()
        onAllNodesWithTag("view-unknown").assertCountEquals(0)
    }
```

- [ ] **Step 2: Run them to see them fail**

Run: `… ./gradlew :ui:jvmTest --tests 'dev.supermux.ui.browser.BrowserPaneTest' --tests 'dev.supermux.ui.shell.ViewHostTest' --max-workers=2`
Expected: compilation FAILS: `BrowserPane` unresolved.

- [ ] **Step 3: Implement `BrowserPane.kt`**

```kotlin
package dev.supermux.ui.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.supermux.browser.AddressAction
import dev.supermux.browser.BrowserTabSync
import dev.supermux.browser.BrowserViewState
import dev.supermux.browser.DisplayAddress
import dev.supermux.browser.addressAction
import dev.supermux.browser.displayAddress
import dev.supermux.browser.nextZoom
import dev.supermux.net.PreviewCookieDto
import dev.supermux.net.ProxyDto
import dev.supermux.proto.ViewDto
import dev.supermux.ui.platform.LocalPlatform
import dev.supermux.webview.Activation
import dev.supermux.webview.InspectorResult
import dev.supermux.webview.LoadingState
import dev.supermux.webview.NewWindowDecision
import dev.supermux.webview.NewWindowRequest
import dev.supermux.webview.ProvideWebViewRuntime
import dev.supermux.webview.WebClient
import dev.supermux.webview.WebContent
import dev.supermux.webview.WebCookie
import dev.supermux.webview.WebSettings
import dev.supermux.webview.WebView
import dev.supermux.webview.WebViewPhase
import dev.supermux.webview.WebViewState
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt
import kotlin.time.TimeSource

/** What a Browser tab shows under its bars. */
internal sealed interface BrowserBody {
    /** A new tab: the address bar waits, and no engine is made. */
    data object NewTab : BrowserBody

    data class Page(val url: String) : BrowserBody

    /** A port on the host with no preview, on a device that isn't the host. */
    data class Offer(val port: Int, val path: String) : BrowserBody

    data class Said(val message: String) : BrowserBody
}

/** A blank page in the tabs' profile: what a tab holds before its first page, so its engine can take a cookie first. */
private val BLANK = WebContent.Html("<!doctype html><title></title>")

/** The tabs' settings: their own profile, and inspectable (they're a developer's browser). */
private val BROWSER_SETTINGS = WebSettings(profile = BROWSER_PROFILE, inspectable = true)

/** The broker's cookie spec, as the engine's cookie. */
internal fun PreviewCookieDto.toWebCookie(): WebCookie = WebCookie(
    name = name, value = value, url = url, path = path, domain = domain,
    secure = secure, httpOnly = httpOnly, sameSite = sameSite, maxAgeSeconds = maxAge,
)

/** A tab's answers to its page: a new window asked for with the user's press opens as a link does. */
private class BrowserClient(private val newWindow: (String) -> Unit) : WebClient {
    override fun onNewWindow(request: NewWindowRequest): NewWindowDecision {
        if (request.isUserGesture) newWindow(request.url)
        return NewWindowDecision.Block
    }
}

/**
 * A Browser tab (#105): a web page under an address bar, in a workspace pane.
 *
 * Its page is the view's synced state. It opens the page stored there, writes each page it commits back with the page's
 * title (which names the tab on every client), and follows a page another client stores ([BrowserTabSync]). Every page
 * goes through [addressAction], so a `localhost` link opens the port's preview on a device that isn't the host, or
 * offers to make one. A preview loads after the host's preview cookie is in the tabs' profile.
 */
@OptIn(FlowPreview::class)
@Composable
fun BrowserPane(view: ViewDto, workspaceId: String, actions: BrowserActions, modifier: Modifier = Modifier) {
    val platform = LocalPlatform.current
    val webViews = LocalAppWebViews.current ?: AppWebViews.app
    val links by rememberUpdatedState(LocalLinkOpener.current)
    val scope = rememberCoroutineScope()
    val sync = remember(view.id) { BrowserTabSync() }
    val state = remember(view.id) { WebViewState(BLANK) }
    val address = remember { FocusRequester() }
    var body by remember(view.id) { mutableStateOf<BrowserBody>(BrowserBody.NewTab) }
    var previews by remember { mutableStateOf<List<ProxyDto>?>(null) }
    var requested by remember(view.id) { mutableStateOf<String?>(null) }
    var finding by remember(view.id) { mutableStateOf(false) }
    var zoom by remember(view.id) { mutableFloatStateOf(1f) }
    var notice by remember(view.id) { mutableStateOf<String?>(null) }

    // A port resolves through the previews, so the first page waits for them: three seconds at most, then none.
    LaunchedEffect(Unit) { previews = withTimeoutOrNull(3_000) { runCatching { actions.proxies() }.getOrNull() } ?: emptyList() }

    fun go(url: String) {
        body = when (val action = addressAction(url, previews.orEmpty(), actions.onHost)) {
            is AddressAction.Load -> BrowserBody.Page(action.url)
            is AddressAction.Offer -> BrowserBody.Offer(action.port, action.path)
            is AddressAction.Say -> BrowserBody.Said(action.message)
        }
    }

    // The view's stored page: the first to open, then another client's (never this tab's own echo). A port waits for
    // the previews, which it resolves through.
    val stored = remember(view.state) { BrowserViewState.from(view.state) }
    val previewsKnown = previews != null
    LaunchedEffect(stored, previewsKnown) {
        if (!previewsKnown) return@LaunchedEffect
        val showing = (body as? BrowserBody.Page)?.url
        sync.toFollow(stored, showing)?.let(::go)
    }

    // Load the page: a preview after its cookie, which goes in through a live engine (the blank page brings one up).
    val page = (body as? BrowserBody.Page)?.url
    val live = state.phase == WebViewPhase.Live
    LaunchedEffect(page, live) {
        val url = page ?: return@LaunchedEffect
        if (url == requested) return@LaunchedEffect
        if (displayAddress(url, previews.orEmpty()) is DisplayAddress.Preview) {
            val cookie = actions.previewCookie()
            if (cookie != null && cookie.url !in webViews.previewCookies) {
                if (!live) return@LaunchedEffect
                if (state.navigator.setCookie(cookie.toWebCookie())) webViews.previewCookies += cookie.url
            }
        }
        requested = url
        state.load(WebContent.Url(url))
    }
    // A zoom outlives the engine: a new lease gets it again.
    LaunchedEffect(live, zoom) { if (live && zoom != 1f) state.navigator.setZoom(zoom) }

    // Each committed page, and its title, back to the view.
    LaunchedEffect(view.id) {
        snapshotFlow { state.url.takeIf { state.content is WebContent.Url } to state.title }
            .debounce(400)
            .collect { (url, title) ->
                val patch = sync.committed(url, title) ?: return@collect
                actions.saveView(workspaceId, view.id, patch.state, patch.title)
            }
    }

    // A new window the page opens on the user's press: the workspace's link rule, at most one a second (a page that
    // answers one press with a burst opens one).
    val windows = remember { Channel<String>(capacity = 4, onBufferOverflow = BufferOverflow.DROP_LATEST) }
    val client = remember { BrowserClient { windows.trySend(it) } }
    LaunchedEffect(Unit) {
        var last: TimeSource.Monotonic.ValueTimeMark? = null
        for (url in windows) {
            if (last != null && last.elapsedNow().inWholeMilliseconds < 1000) continue
            last = TimeSource.Monotonic.markNow()
            links?.open(url, view.id, modified = false) ?: platform.openUrl(url)
        }
    }

    fun submit(text: String) {
        if (text.isBlank()) return
        val before = page
        go(text)
        if (before != null && (body as? BrowserBody.Page)?.url == before) state.navigator.reload()
    }
    fun zoomBy(direction: Int) {
        val next = if (direction == 0) 1f else nextZoom(zoom, direction)
        if (state.navigator.setZoom(next)) zoom = next else notice = "Zoom isn't available here yet"
    }
    fun inspect() {
        notice = when (val r = state.navigator.openInspector()) {
            InspectorResult.Opened -> null
            is InspectorResult.Elsewhere -> r.hint
            else -> "This engine has no inspector here"
        }
    }

    val shown = state.url.takeIf { state.content is WebContent.Url } ?: page
    Column(
        modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).testTag("browser-${view.id}")
            .onPreviewKeyEvent { event ->
                when (browserShortcut(event)) {
                    BrowserShortcut.FocusAddress -> address.requestFocus()
                    BrowserShortcut.Reload -> state.navigator.reload()
                    BrowserShortcut.Find -> finding = true
                    BrowserShortcut.Back -> state.navigator.back()
                    BrowserShortcut.Forward -> state.navigator.forward()
                    BrowserShortcut.ZoomIn -> zoomBy(+1)
                    BrowserShortcut.ZoomOut -> zoomBy(-1)
                    BrowserShortcut.ZoomReset -> zoomBy(0)
                    null -> return@onPreviewKeyEvent false
                }
                true
            },
    ) {
        val loading = state.loading is LoadingState.Loading
        BrowserToolbar(
            shown = shown?.let { displayAddress(it, previews.orEmpty()).text() }.orEmpty(),
            full = shown,
            canGoBack = state.canGoBack,
            canGoForward = state.canGoForward,
            loading = loading,
            zoomPercent = (zoom * 100).roundToInt(),
            address = address,
            onBack = { state.navigator.back() },
            onForward = { state.navigator.forward() },
            onReload = { if (loading) state.navigator.stop() else state.navigator.reload() },
            onSubmit = ::submit,
            onOpenExternally = { shown?.let(platform::openUrl) },
            onFind = { finding = true },
            onZoom = ::zoomBy,
            onInspect = ::inspect,
        )
        notice?.let { text ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp).testTag("browser-notice"), verticalAlignment = Alignment.CenterVertically) {
                Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                TextButton(onClick = { notice = null }) { Text("OK") }
            }
        }
        if (finding) {
            FindBar(
                onFind = { query, backwards -> state.navigator.find(query, backwards) },
                onClose = { finding = false; state.navigator.clearFind() },
            )
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (val b = body) {
                BrowserBody.NewTab -> BrowserMessage("Type a site, or a port such as 5173.", "browser-new-tab")
                is BrowserBody.Said -> BrowserMessage(b.message, "browser-said")
                is BrowserBody.Offer -> PortOffer(b.port, onMake = { done ->
                    scope.launch {
                        val made = actions.makePreview(workspaceId, b.port)
                        if (made != null) {
                            previews = previews.orEmpty() + made
                            go("http://localhost:${b.port}${b.path}")
                        }
                        done(made != null)
                    }
                })
                is BrowserBody.Page -> {
                    ProvideWebViewRuntime(webViews.runtime) {
                        WebView(state, Modifier.fillMaxSize(), settings = BROWSER_SETTINGS, client = client, activation = Activation.Eager)
                    }
                    (state.phase as? WebViewPhase.Unavailable)?.let { unavailable ->
                        BrowserMessage(
                            "This device can't show web pages in a tab" + (unavailable.detail?.let { ": $it" } ?: "."),
                            "browser-unavailable",
                            "Open in your browser" to { platform.openUrl(b.url) },
                        )
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 4: `ViewHost` draws it.** In `ViewHost.kt`'s `when (view.kind)`, before `else -> UnknownViewHint(view.kind, modifier)`:

```kotlin
        // A web page (#105): its view's state is the page, synced to every client.
        "browser" -> dev.supermux.ui.browser.BrowserPane(view, workspaceId, actions.browser, modifier)
```

- [ ] **Step 5: Run the tests**

Run: the Step 2 command.
Expected: PASS. If `aPreviewTakesTheCookieBeforeItLoads` loads before the cookie: the blank page must reach `Live` first, so check that `FakeEngineBehavior()` paints on load (`paintsOnLoad`). Fix the pane, not the test.

- [ ] **Step 6: Commit**

```bash
git add apps/ui/src/commonMain/kotlin/dev/supermux/ui/browser/BrowserPane.kt apps/ui/src/commonMain/kotlin/dev/supermux/ui/shell/ViewHost.kt apps/ui/src/jvmTest
git commit -m "feat(browser): the Browser pane: a synced page under its bars, previews through their cookie, ports offered (#105)"
```

---

### Task 8: Chat links open in Browser tabs (⌘/Ctrl-click → the system browser; the web client keeps its own tabs)

**Files:**
- Modify: `apps/ui/src/commonMain/kotlin/dev/supermux/ui/chat/Markdown.kt:136-138` (`mdAnnotated`'s `openUrl`)
- Modify: `apps/ui/src/commonMain/kotlin/dev/supermux/ui/shell/ViewHost.kt:217-232` (the `"chat"` branch)
- Modify: `apps/ui/src/commonMain/kotlin/dev/supermux/ui/workspace/WorkspaceSession.kt` (`browserOpener`; `postView` takes the kind)
- Modify: `apps/ui/src/commonMain/kotlin/dev/supermux/ui/shell/SupermuxApp.kt:2039-2047` (`postView`) and `:1906-1951` (the panes, inside a provider)
- Modify: `apps/ui/src/commonMain/kotlin/dev/supermux/ui/platform/Platform.kt` (`Caps.linksInBrowserTabs`)
- Modify: `apps/web/src/wasmJsMain/kotlin/dev/supermux/web/WebPlatform.kt:49` (`WEB_CAPS`)
- Test: `apps/ui/src/jvmTest/kotlin/dev/supermux/ui/browser/LinkOpenerTest.kt`

- [ ] **Step 1: Write the failing tests**

```kotlin
package dev.supermux.ui.browser

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.supermux.ui.chat.MarkdownBody
import dev.supermux.ui.chat.setPlatformContent
import dev.supermux.ui.platform.FakePlatform
import dev.supermux.ui.platform.NO_CAPS
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class LinkOpenerTest {
    @Test fun aChatLinkGoesToTheOpenerItsChatProvides() = runComposeUiTest {
        val opened = mutableListOf<String>()
        val platform = FakePlatform()
        setPlatformContent(platform = platform) {
            CompositionLocalProvider(LocalOpenLink provides { url: String -> opened += url }) { MarkdownBody("[Vite](https://vitejs.dev/)") }
        }
        onNodeWithText("Vite").performClick()
        assertEquals(listOf("https://vitejs.dev/"), opened)
        assertTrue(platform.openedUrls.isEmpty())
    }

    @Test fun withNoOpenerALinkOpensInTheSystemBrowserAsBefore() = runComposeUiTest {
        val platform = FakePlatform()
        setPlatformContent(platform = platform) { MarkdownBody("[Vite](https://vitejs.dev/)") }
        onNodeWithText("Vite").performClick()
        assertEquals(listOf("https://vitejs.dev/"), platform.openedUrls)
    }

    @Test fun aPressWithCtrlOrCmdIsRememberedForTheLinkItOpens() = runComposeUiTest {
        val clicks = ClickModifiers()
        setPlatformContent { Box(Modifier.size(100.dp).testTag("t").focusable().trackClickModifiers(clicks)) }
        onNodeWithTag("t").performMouseInput { click() }
        assertFalse(clicks.modified)
        onNodeWithTag("t").requestFocus()
        onNodeWithTag("t").performKeyInput { keyDown(Key.CtrlLeft) }
        onNodeWithTag("t").performMouseInput { click() }
        assertTrue(clicks.modified)
    }

    @Test fun chatLinksOpenInBrowserTabsUnlessThePlatformSaysOtherwise() {
        // The web client's WEB_CAPS says otherwise (Ahmet's Q5); `:ui`'s tests can't see `:web`, so the review checks it.
        assertTrue(NO_CAPS.linksInBrowserTabs)
    }
}
```

- [ ] **Step 2: Run them to see them fail**

Run: `… ./gradlew :ui:jvmTest --tests 'dev.supermux.ui.browser.LinkOpenerTest' --max-workers=2`
Expected: `aChatLinkGoesToTheOpenerItsChatProvides` FAILS (the link goes to `platform.openUrl`), and `linksInBrowserTabs` doesn't compile.

- [ ] **Step 3: Markdown asks the chat's opener first.** In `mdAnnotated`, replace

```kotlin
    val openUrl: (String) -> Unit = onOpenUrl ?: { url -> runCatching { platform.openUrl(url) } }
```

with

```kotlin
    // A chat in a workspace routes its links (#105): the Browser tab that shows the link, a new one, or, on a
    // ⌘/Ctrl-click, the system's browser. Outside one, and on the web client, links open as they always did.
    val routed = dev.supermux.ui.browser.LocalOpenLink.current
    val openUrl: (String) -> Unit = onOpenUrl ?: routed ?: { url -> runCatching { platform.openUrl(url) } }
```

- [ ] **Step 4: The capability.** In `Caps`, after `val assistantOverlay: Boolean = false,`:

```kotlin
    /**
     * A link from chat opens in a Browser tab of the workspace (#105). False on the web client, where a link opens a
     * tab of the browser the app runs in (Ahmet's Q5).
     */
    val linksInBrowserTabs: Boolean = true,
```

and in `WEB_CAPS`, add `linksInBrowserTabs = false,`.

- [ ] **Step 5: A chat provides its links' opener.** In `ViewHost.kt`, wrap the `"chat"` branch's `ChatViewPane(…)` call, so the branch reads:

```kotlin
        "chat" -> {
            val sessionId = view.chatSessionId()
            if (sessionId == null) UnknownViewHint(view.kind, modifier)
            else {
                // Its links go through the workspace (#105), with the modifiers of the press that clicked them.
                val links = dev.supermux.ui.browser.LocalLinkOpener.current
                val clicks = remember { dev.supermux.ui.browser.ClickModifiers() }
                val openLink = remember(links, view.id) { links?.let { o -> { url: String -> o.open(url, view.id, clicks.modified) } } }
                CompositionLocalProvider(dev.supermux.ui.browser.LocalOpenLink provides openLink) {
                    ChatViewPane(
                        // every argument as it is, with `modifier` replaced by:
                        modifier = modifier.then(Modifier.trackClickModifiers(clicks)),
                    )
                }
            }
        }
```

Keep every `ChatViewPane` argument as it is today, and change only its `modifier`. Import `androidx.compose.runtime.CompositionLocalProvider` and `dev.supermux.ui.browser.trackClickModifiers`.

- [ ] **Step 6: The workspace's opener.** In `WorkspaceSession.kt`:
  - Import `dev.supermux.workspace.WorkspaceBrowserOpener`.
  - Add the constructor parameter `val browserOpener: WorkspaceBrowserOpener? = null,` after `val fileOpener: WorkspaceFileOpener,`. It is nullable, so the tests that build a `WorkspaceSession` themselves keep compiling.
  - Change `rememberWorkspaceSession`'s parameter to `postView: suspend (id: String, kind: String, state: JsonObject, groupId: String) -> String?`.
  - In `rememberWorkspaceSession`, change the file opener's `post = postView,` to `post = { id, state, groupId -> postView(id, "editor", state, groupId) },`.
  - Make the Browser opener after `fileOpener`, and pass `browserOpener = browserOpener,` to `WorkspaceSession(…)`:

```kotlin
    val platform = dev.supermux.ui.platform.LocalPlatform.current
    // Links open in this workspace's Browser tabs (#105), placed like files: beside the other tabs, else right of the chat.
    val browserOpener = WorkspaceBrowserOpener(
        workspaceId = workspace.id,
        treeOf = { layoutSync.tree },
        viewsOf = { provisionalViews.toMap() + workspace.views.associateBy { it.id } },
        edit = { transform -> layoutSync.edit(transform) },
        provisional = provisionalViews,
        post = { id, state, groupId -> postView(id, "browser", state, groupId) },
        openSystem = { url -> platform.openUrl(url) },
        scope = overlayScope,
        newId = newId,
    )
```

  Then run `grep -rn "postView = " apps/ui/src` and give every other caller (tests included) the `kind` parameter.

- [ ] **Step 7: `SupermuxApp` posts the kind it's given, and provides the opener.** In `rememberHostWorkspaceSession`, the `postView` lambda becomes:

```kotlin
    postView = { id, kind, state, groupId ->
        val created = runCatching {
            wsApp.api.addView(current.id, AddViewBody(kind = kind, state = state, id = id, groupId = groupId))
        }.onFailure { println("[SupermuxApp] open $kind view failed: $it") }.getOrNull()?.id
        if (created != null && activateOpenedFile) wsApp.setActiveView(current.id, created)
        created
    },
```

In `WorkspacePanel`, put the `if (compact) { PhoneWorkspacePanes(…) } else { WorkspacePanes(…) }` block (`:1906-1951`) inside this provider, unchanged:

```kotlin
    // Chat links open in this workspace's Browser tabs (#105). The opener is rebuilt every composition, so the
    // LinkOpener reads the latest one: a new LinkOpener each time would recompose every chat under it.
    val browserOpener by rememberUpdatedState(ws.browserOpener)
    val linksInTabs = LocalPlatform.current.caps.linksInBrowserTabs
    val linkOpener = remember(linksInTabs, current.id, compact) {
        if (!linksInTabs) null
        else dev.supermux.ui.browser.LinkOpener { url, source, modified ->
            browserOpener?.open(url, source, modified, onPlaced = { id -> if (compact) wsApp.setActiveView(current.id, id) })
        }
    }
    CompositionLocalProvider(dev.supermux.ui.browser.LocalLinkOpener provides linkOpener) {
        // ← the existing `if (compact) { … } else { … }` block, moved in as it is
    }
```

On a phone, a link's tab, new or brought forward, becomes the active view, the way a chat row's tab does (`chatTabFocus`).

- [ ] **Step 8: Run the tests, then the chat and shell suites the change touches**

Run: `… ./gradlew :ui:jvmTest --tests 'dev.supermux.ui.browser.*' --tests 'dev.supermux.ui.chat.Markdown*' --tests 'dev.supermux.ui.shell.ViewHostTest' --tests 'dev.supermux.ui.workspace.*' --max-workers=2`
Expected: PASS. If `aPressWithCtrlOrCmdIsRememberedForTheLinkItOpens` fails because the test's mouse events carry no key state, give `ClickModifiers` an internal setter test instead, and check the real ⌘-click on the Mac in Task 10.

- [ ] **Step 9: Compile the web client** (it sets the new capability)

Run: `… ./gradlew :web:compileKotlinWasmJs --max-workers=2`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 10: Commit**

```bash
git add apps/ui apps/web
git commit -m "feat(browser): chat links open in the workspace's Browser tabs; ⌘/Ctrl-click and the web client keep the system's (#105)"
```

---

### Task 9: The page's icon on its tab

**Files:**
- Modify: `apps/ui/src/commonMain/kotlin/dev/supermux/ui/browser/AppWebViews.kt` (`LocalBrowserIcons`)
- Modify: `apps/ui/src/commonMain/kotlin/dev/supermux/ui/browser/BrowserPane.kt` (ask the page for its icon after each commit)
- Modify: `apps/ui/src/commonMain/kotlin/dev/supermux/ui/shell/WorkspacePanes.kt:1383-1398` (`ViewTabIcon`)
- Modify: `apps/ui/src/commonMain/kotlin/dev/supermux/ui/shell/SupermuxApp.kt` (the provider from Task 8 provides the icons too)
- Test: `apps/ui/src/jvmTest/kotlin/dev/supermux/ui/browser/BrowserPaneTest.kt` (one test)

The icon is kept by this client for each view, and it isn't synced: the broker refuses keys other than `url` and `doc` in a Browser tab's state. Another client shows the globe until it opens the tab itself.

- [ ] **Step 1: Write the failing test.** Append to `BrowserPaneTest`. The `show` helper gains an `icons: SnapshotStateMap<String, String>? = null` parameter, provided as `LocalBrowserIcons provides icons`.

```kotlin
    @Test fun eachCommittedPageTellsItsTabItsIcon() = runComposeUiTest {
        val rig = Rig()
        val icons = mutableStateMapOf<String, String>()
        show(rig, mutableStateOf(view("https://a.test/")), rig.actions(), icons = icons)
        waitUntil(timeoutMillis = 5_000) { "https://a.test/" in rig.loads() }
        rig.engines.created.forEach { it.evaluateAnswer = { "\"https://vitejs.dev/logo.svg\"" } }
        onNodeWithTag("browser-address").performClick()
        onNodeWithTag("browser-address").performTextReplacement("vitejs.dev")
        onNodeWithTag("browser-address").performKeyInput { pressKey(Key.Enter) }
        waitUntil(timeoutMillis = 5_000) { icons["b1"] == "https://vitejs.dev/logo.svg" }
    }
```

- [ ] **Step 2: Run it to see it fail**

Run: `… ./gradlew :ui:jvmTest --tests 'dev.supermux.ui.browser.BrowserPaneTest' --max-workers=2`
Expected: compilation FAILS: `LocalBrowserIcons` unresolved.

- [ ] **Step 3: Implement.** In `AppWebViews.kt`:

```kotlin
/** Each Browser tab's page icon, by view id: the workspace's, so its tab strip can draw them (#105). Not synced. */
val LocalBrowserIcons = staticCompositionLocalOf<androidx.compose.runtime.snapshots.SnapshotStateMap<String, String>?> { null }
```

In `BrowserPane`, after the zoom effect:

```kotlin
    // The page's icon for its tab, after each page it commits.
    val icons = LocalBrowserIcons.current
    LaunchedEffect(state.url, live) {
        if (!live || icons == null || state.content !is WebContent.Url) return@LaunchedEffect
        state.navigator.favicon()?.takeIf { it.startsWith("https://") || it.startsWith("http://") }?.let { icons[view.id] = it }
    }
```

In `ViewTabIcon`, first thing after the agent-logo case:

```kotlin
    if (v?.kind == "browser") {
        dev.supermux.ui.browser.LocalBrowserIcons.current?.get(v.id)?.let { icon ->
            coil3.compose.AsyncImage(
                model = icon,
                contentDescription = null,
                // A preview's icon needs the tab's cookie, which this request doesn't carry: the globe instead.
                error = androidx.compose.ui.graphics.vector.rememberVectorPainter(Icons.Outlined.Language),
                modifier = Modifier.size(15.dp),
            )
            return
        }
    }
```

In Task 8's provider, add `val browserIcons = remember(current.id) { mutableStateMapOf<String, String>() }` and provide `dev.supermux.ui.browser.LocalBrowserIcons provides browserIcons` alongside `LocalLinkOpener`.

- [ ] **Step 4: Run the tests**

Run: the Step 2 command.
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add apps/ui
git commit -m "feat(browser): a Browser tab shows its page's icon (#105)"
```

---

### Task 10: Check it all, try it on real engines, and hand it over

- [ ] **Step 1: Every test the plan touched, and the targets this box builds.**

Run: `… ./gradlew :shared:jvmTest --tests 'dev.supermux.browser.*' --tests 'dev.supermux.workspace.*' --tests 'dev.supermux.util.TransportPolicyTest' --tests 'dev.supermux.net.BrokerApiPreviewTokenTest' :ui:jvmTest --tests 'dev.supermux.ui.browser.*' --tests 'dev.supermux.ui.shell.*' --tests 'dev.supermux.ui.chat.Markdown*' --tests 'dev.supermux.ui.workspace.*' :ui:compileKotlinWasmJs :ui:compileDebugKotlinAndroid :web:compileKotlinWasmJs --max-workers=2`
Expected: PASS, BUILD SUCCESSFUL.

- [ ] **Step 2: The Mac, real WebKit.** This needs the 2a car's glue, built on the Mac. Follow Tally's terms: a `.lease` in `~/work/supermux-hotreload`, nice, one Gradle worker, no VM, and load and swap checked twice before starting. Ping Tally at the start and at the finish, and leave things clean. Then `:desktop:run` against the live broker, and check:
  1. New tab → Browser (row 6). Type `vitejs.dev`: the page loads, and its title and icon land on the tab.
  2. In a chat, click a link: a new Browser tab opens to the right. Click the same link again: that tab comes forward. ⌘-click it: Safari opens it.
  3. In a chat, click `http://localhost:<an exposed port>`: the tab opens the preview with no 401. That proves the cookie, which needs #105 car 2 live.
  4. ⌘F finds, ⌘= zooms, ⌘R reloads, ⌘L selects the address.
  5. Open the tab on a second client (the web app): the page follows.

  Record what was seen in the card, with screenshots.

- [ ] **Step 3: The phone.** Not without asking Tally first: the Fold is Ahmet's. Android's engine has no find, zoom or cookie until plan 2b, so check only the New tab page's row 6, a link from chat, and full screen.

- [ ] **Step 4: Push, and hand the car over after 2a.** Once 2a's car (`mux/browser-library-mac`) is on dev:

```bash
git fetch origin && git rebase origin/dev
git push -u origin mux/browser-tab
```

Rebasing drops the two merges, because their commits are on dev by then. Send Muxie `mux/browser-tab @ <sha>`: "#105 car 3, the Browser tab in the app; rides after 2a; needs car 2 live for previews". Comment on card #105 with what Step 2 showed.

- [ ] **Step 5: Shared memory.** Append to `~/.mux/domains/claudemux.md` under `## #105 the Browser tab in the app (YYYY-MM-DD)`:
  - where it lives (`ui/browser`, `shared/browser`, `WorkspaceBrowserOpener`);
  - the rules it follows: same-link reuse, ⌘-click, the web client, ports through previews, the offer;
  - the preview cookie sequence: a blank page, then the cookie, then the page;
  - favicons are local;
  - its keys stay pane-local until #96 phase 2.
