# Windows/Linux Desktop Client — Milestone 2 (Terminal) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the "Terminal — arrives in M2" placeholder with a real JediTerm terminal: the per-session **scratch shell with web-parity tabs** (list/add/close) and the **agent PTY view** (Chat|Native toggle for claude sessions), fed by the shared `TerminalClient` over the existing WS, with wheel→tmux scroll and the shared predictive-echo engine — per the M2 section of `docs/superpowers/specs/2026-07-09-windows-linux-desktop-client-design.md`.

**Architecture:** JediTerm 3.73 (Swing `JediTermWidget` in a `SwingPanel`) renders the raw tmux byte-stream; a `MuxTtyConnector` bridges shared `TerminalClient` ↔ JediTerm (streaming UTF-8 decode on read, bytes on write, resize passthrough). Prediction = a desktop `PredictionAdapter` that renders shared-engine `DisplayOp`s as ANSI escapes injected into the same char stream (the Android/termlib approach), reading cursor/cells via JediTerm's PUBLIC APIs (no reflection — the Android hack was termlib-specific). JediTerm's built-in typeahead is disabled (one prediction system only).

**Tech Stack:** `org.jetbrains.jediterm:jediterm-core:3.73` + `jediterm-ui:3.73` from `https://packages.jetbrains.team/maven/p/ij/intellij-dependencies` (already in the catalog + module repos, resolution UNVERIFIED — Task 1 smoke-tests it). Dual-licensed LGPLv3/Apache-2.0 → we use it under **Apache-2.0** (note in the module build file).

---

## Ground rules (read before Task 1)

All M1 rules hold. The distilled environment facts:

- **Gradle:** `mkdir -p /home/ahmet/.cache/tmp; cd apps && TMPDIR=/home/ahmet/.cache/tmp ./gradlew --no-daemon -Dorg.gradle.jvmargs=-Xmx2048M <task> > /home/ahmet/.cache/desktop-build.log 2>&1; tail -20 /home/ahmet/.cache/desktop-build.log`. Never write big output to /tmp (quota). If a build stalls >3min on "Waiting to acquire" another agent holds the lock — retry once.
- **Headless runs:** `Xvfb :77 -screen 0 1600x1000x24 &` then `DISPLAY=:77 SKIKO_RENDER_API=SOFTWARE XDG_CONFIG_HOME=/home/ahmet/.cache/smx-test-config TMPDIR=/home/ahmet/.cache/tmp ./gradlew --no-daemon :desktop:run > /home/ahmet/.cache/desktop-run.log 2>&1 &`. `SKIKO_RENDER_API=SOFTWARE` is MANDATORY. Screenshots: `xwd -display :77 -root -out x.xwd` + the Pillow XWD(BGRX-32bpp)→PNG converter (`/home/ahmet/.cache/xwd2png.py`, recreate if missing). Read PNGs to verify. Kill app + Xvfb after each run. NO xdotool — input verification goes through env hooks + tests.
- **Env verification hooks (exist, off by default):** `SM_PAIR_TOKEN`+`SM_PAIR_BASE` (both required), `SM_AUTOSELECT=1` (defers to a persisted `selectedId` — clear `ui-state.json` in the config dir for deterministic runs), `SM_PANES=etd` (any subset of e/t/d), `SM_SMOKE_SEND="name:text"`. This plan adds `SM_TERM_INPUT` (Task 8).
- **Live broker** ws://127.0.0.1:9898; paired config `XDG_CONFIG_HOME=/home/ahmet/.cache/smx-test-config`. Never restart/reconfigure the broker. tmux sessions survive WS detach — connecting/disconnecting terminals is safe. Typing into the SCRATCH terminal of a throwaway session is allowed; NEVER inject input into the AGENT terminal of a working session (that types into a live Claude TUI) — for agent-terminal verification use a session that is IDLE and observe output only.
- **Shared-module changes:** Task 6 adds two `BrokerApi` endpoints (commonMain). Any task touching `apps/shared` must re-run `:shared:jvmTest` AND `:android:compileDebugKotlin` (and note that `:shared:allTests` common tests run under jvmTest on this box).
- **Commit trailer:** `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`.
- **Suite baseline:** desktop 74 green / shared jvmTest 288 green at HEAD. Keep both green after every task.

---

### Task 1: JediTerm dependency smoke + headless render proof

**Files:**
- Modify: `apps/desktop/build.gradle.kts` (add `implementation(libs.jediterm.core)` + `implementation(libs.jediterm.ui)` + a `// JediTerm is dual LGPLv3/Apache-2.0; used under Apache-2.0` comment)
- Create: `apps/desktop/src/test/kotlin/dev/supermux/desktop/terminal/JediTermSmokeTest.kt`

- [ ] **Step 1:** Add the two deps. Run `:desktop:compileKotlin` — this is the first real resolution of the intellij-dependencies coordinates (they were pre-declared in M1 but never fetched). If resolution fails, check the artifact names on `https://packages.jetbrains.team/maven/p/ij/intellij-dependencies/org/jetbrains/jediterm/` (WebFetch) and correct catalog coordinates; report the correction.
- [ ] **Step 2:** Write `JediTermSmokeTest`: headless-construct the MODEL layer only (no Swing frame): a `com.jediterm.terminal.model.TerminalTextBuffer` + `JediTerminal` with a `StyleState` (read JediTerm's `BasicTerminalShellExample` + core sources under `~/.gradle` caches or GitHub to get exact constructor shapes), write bytes like `"hello\r\n"` through the emulator, assert the buffer contains `hello` and cursor moved. This proves the core API surface we depend on (buffer read + cursor read + programmatic write) without AWT. If pure-model construction needs a `JediTermWidget` instead, use one with `java.awt.headless=false` under the test only if unavoidable — prefer the model path and DOCUMENT which worked.
- [ ] **Step 3:** Commit `feat(desktop): JediTerm dependency + core-model smoke test`.

---

### Task 2: `MuxTtyConnector` — TerminalClient ↔ JediTerm bridge (TDD)

**Files:**
- Create: `apps/desktop/src/main/kotlin/dev/supermux/desktop/terminal/MuxTtyConnector.kt`
- Create: `apps/desktop/src/test/kotlin/dev/supermux/desktop/terminal/MuxTtyConnectorTest.kt`

Design (write tests FIRST against this contract):

```kotlin
/** Bridges the shared TerminalClient byte-stream to JediTerm's char-based TtyConnector.
 *  Also the injection point for predictive-echo ops: [injectDisplayBytes] enqueues synthetic
 *  ANSI escapes into the same queue as server output so the emulator renders them in order. */
class MuxTtyConnector(
    private val sendInput: (ByteArray) -> Unit,      // -> client.sendInput
    private val requestResize: (cols: Int, rows: Int) -> Unit, // -> scope.launch { client.resize }
    private val isConnected: () -> Boolean,          // -> client.status.value == CONNECTED
    name: String = "supermux",
) : TtyConnector {
    fun offerServerBytes(bytes: ByteArray)   // called from client.output collector
    fun injectDisplayBytes(bytes: ByteArray) // prediction adapter path (same queue)
    fun closeStream()                        // unblocks read() with -1 → emulator thread ends
    // TtyConnector: read(char[],off,len) blocks on the queue with a STREAMING UTF-8 decoder
    // (a multi-byte sequence split across two WS frames must decode correctly — use
    // CharsetDecoder with CodingErrorAction.REPLACE and carry the undecoded tail between reads);
    // write(bytes)=sendInput; write(String)=sendInput(utf8); resize(TermSize)=requestResize;
    // isConnected()=isConnected; waitFor() blocks until closeStream; ready() = queue non-empty.
}
```

- [ ] **Step 1:** Tests: (a) offerServerBytes("hi") → read() returns "hi"; (b) a 3-byte UTF-8 char (e.g. "→" 0xE2 0x86 0x92) split across two offerServerBytes calls decodes to one char, not replacement garbage; (c) write(bytes) forwards to sendInput verbatim; (d) resize forwards cols/rows; (e) closeStream → read() returns -1 and waitFor() unblocks; (f) injectDisplayBytes interleaves in order with server bytes. Run → FAIL (unresolved).
- [ ] **Step 2:** Implement. `LinkedBlockingQueue<ByteArray>` + a carry buffer for the decoder tail. read() must never busy-spin.
- [ ] **Step 3:** Green; commit `feat(desktop): MuxTtyConnector — WS byte-stream to JediTerm char bridge`.

---

### Task 3: `DesktopTerminalPanel` composable — SwingPanel embed, theme, lifecycle

**Files:**
- Create: `apps/desktop/src/main/kotlin/dev/supermux/desktop/terminal/DesktopTerminalPanel.kt`
- Create: `apps/desktop/src/main/kotlin/dev/supermux/desktop/terminal/SupermuxTermSettings.kt`
- Create: `apps/desktop/src/main/kotlin/dev/supermux/desktop/ui/KeepAlivePanel.kt` (port of Android `ui/KeepAlivePanel.kt`)

Read Android's `terminal/TerminalPanel.kt` first — mirror its structure where the platform allows.

- [ ] **Step 1:** `SupermuxTermSettings : DefaultSettingsProvider`: terminal bg/fg from the theme's pane colors (shared `SupermuxColors.terminal`/`terminalForeground` — dark `0xFF050605`/`0xFFD8DED3`; resolve via the desktop theme like Android's `LocalPanes`), monospace font = **Geist Mono from the bundled resources** (load `Font.createFont(TRUETYPE_FONT, resourceStream)` once; fall back to `Font.MONOSPACED` if load fails), sensible size (13f), **typeahead DISABLED** (override the typeahead-enabled setting — find its exact name in `SettingsProvider`; our shared engine is the only prediction system) and audible bell off.
- [ ] **Step 2:** `DesktopTerminalPanel(connect: () -> TerminalClient, modifier, active: Boolean = true, onExit: (() -> Unit)? = null)`:
  - `remember(client)`: build `MuxTtyConnector` + `JediTermWidget(80, 24, settings)`; `widget.ttyConnector = connector; widget.start()`.
  - Collect `client.output` → `pred.handleOutput(bytes) { connector.offerServerBytes(bytes) }` (prediction pipeline arrives Task 5 — until then call `connector.offerServerBytes` directly and leave a `// Task 5 wires the prediction pipeline here` marker).
  - `LaunchedEffect(client) { client.run() }`; `DisposableEffect(client) { onDispose { client.stop(); connector.closeStream(); widget.close() } }`.
  - Agent-exit: latch CONNECTED→DISCONNECTED like Android (`hadConnected`) → `onExit?.invoke()`.
  - Status chip overlay (CONNECTING/DISCONNECTED) ported from Android's `StatusChip`.
  - Embed via `SwingPanel(factory = { widget }, modifier = modifier.fillMaxSize())`. Resize: JediTerm recomputes its grid from the Swing component size and calls `TtyConnector.resize` → already bridged. Focus: click focuses the widget (Swing default); do NOT auto-focus on composition (Android rule).
- [ ] **Step 3:** `KeepAlivePanel` port: desktop version = `Modifier.keepAlivePanel(visible)` (zIndex/alpha 0 + block pointer input when hidden). ⚠️ SwingPanel is a HEAVYWEIGHT AWT child — alpha/zIndex do NOT hide it (known Compose interop limitation). Handle visibility for SwingPanel content by sizing it to 0×0 when hidden (`Modifier.size(0.dp)` swap) OR removing it while keeping the `client`/`widget` alive in a remembered holder outside the SwingPanel (widget survives unmount; SwingPanel re-factory returns the SAME widget instance — the M1 web-tab isolation isn't violated since it's one session's pane). Choose whichever actually works under test, DOCUMENT it, and assert the client stays CONNECTED across a hide/show cycle in a UI test if feasible.
- [ ] **Step 4:** Compile + commit `feat(desktop): JediTerm terminal panel — SwingPanel embed, theme, lifecycle, status chip`.

---

### Task 4: Wheel→tmux scroll bridge

**Files:**
- Modify: `apps/desktop/src/main/kotlin/dev/supermux/desktop/terminal/DesktopTerminalPanel.kt`
- Create: `apps/desktop/src/test/kotlin/dev/supermux/desktop/terminal/TerminalWheelTest.kt`

- [ ] **Step 1:** JediTerm's own wheel handling scrolls LOCAL scrollback — inert under tmux alt-screen + `mouse on` (same as SwiftTerm-mac and termlib; verified pattern). Attach a `java.awt.event.MouseWheelListener` to the widget's terminal panel component that: computes cell height from the widget's char size, accumulates `e.preciseWheelRotation * scrollAmount` px (or rotation→lines directly via `linesFromPixels` with the carry pattern), maps via shared `wheelEventsFromLines(lines, cols/2, rows/2)`, and `client.sendInput(bytes)` — consuming the event so JediTerm's local scroll doesn't also fire. Extract the accumulate+emit logic into a pure `WheelAccumulator` class for testing.
- [ ] **Step 2:** Tests on `WheelAccumulator`: sub-line accumulation carries; one SGR up/down sequence per line with correct sign; center-cell coordinates; zero on non-finite. (The shared math is already parity-tested — these tests cover the desktop glue only.)
- [ ] **Step 3:** Commit `feat(desktop): terminal wheel→tmux SGR scroll bridge (shared TerminalScroll math)`.

---

### Task 5: Predictive-echo adapter + pipeline (TDD)

**Files:**
- Create: `apps/desktop/src/main/kotlin/dev/supermux/desktop/terminal/PredictionAdapter.kt`
- Create: `apps/desktop/src/main/kotlin/dev/supermux/desktop/terminal/PredictionPipeline.kt`
- Modify: `DesktopTerminalPanel.kt` (wire the pipeline at the Task-3 marker)
- Create: `apps/desktop/src/test/kotlin/dev/supermux/desktop/terminal/PredictionAdapterTest.kt`

- [ ] **Step 1:** `PredictionAdapter(widget)` mirrors Android's op-renderer EXCEPT: rendering injects escapes via `connector.injectDisplayBytes(...)` (ordered with server bytes — no interleaving race, unlike rendering on another thread), and cursor/cell reads use JediTerm's PUBLIC model: `widget.terminal.cursorX/cursorY` (check 0- vs 1-based against `CursorPos`'s convention — the engine uses the SAME convention as the server echo; read the Android adapter's handling and match semantics, document the mapping) + `widget.terminalTextBuffer.getCharAt(x, y)` under `buffer.lock()/unlock()`. Same escape constants (`ESC[?25l/h`, CUP, `ESC[2m`/`ESC[22m`), same exhaustive `when` over `DisplayOp`, same snapshot-cache with eviction. NO reflection, no `available` probe needed (public API) — but keep a cheap `available` returning true for pipeline-shape parity with Android.
- [ ] **Step 2:** `PredictionPipeline` port (attach/handleInput/handleOutput/teardown, EWMA latency bootstrap from keystroke→echo RTT, output-render guarded with fallback, monotonic clock = `System.nanoTime()/1_000_000`). Input taps: JediTerm sends user input through `TtyConnector.write` — route `handleInput(data)` there (in `MuxTtyConnector.write`, via an optional `onUserInput: (ByteArray) -> Unit` hook installed by the pipeline) BEFORE sendInput, mirroring Android's ordering.
- [ ] **Step 3:** Tests against a real headless JediTerm model (Task 1 proved constructibility): feed a prompt line, run engine ops through the adapter, assert buffer cells show the dim-drawn char at the right position and RestoreCell puts the original back; assert Passthrough bytes reach the emulator; assert cursor mapping convention (write a char, compare adapter.cursor() to the engine's expectation after a server echo). The ENGINE itself stays covered by the 26 shared parity tests — do not re-test engine logic.
- [ ] **Step 4:** Wire into `DesktopTerminalPanel` (pipeline attach on the widget, handleInput hook, handleOutput wrapping). Typeahead-off (Task 3) must be verified here: assert via settings that JediTerm's own typeahead can't double-draw.
- [ ] **Step 5:** Green (all suites); commit `feat(desktop): predictive echo — JediTerm adapter over public buffer/cursor APIs + pipeline`.

---

### Task 6: Shared `BrokerApi` terminal-tab endpoints + scratch tab strip

**Files:**
- Modify: `apps/shared/src/commonMain/kotlin/dev/supermux/net/BrokerApi.kt` (+2 methods + DTO)
- Create: `apps/shared/src/commonTest/kotlin/dev/supermux/net/BrokerApiTerminalsTest.kt`
- Create: `apps/desktop/src/main/kotlin/dev/supermux/desktop/terminal/TerminalTabs.kt`
- Modify: `apps/desktop/src/main/kotlin/dev/supermux/desktop/state/DesktopAppState.kt` (connect factories + tab methods)

- [ ] **Step 1 (shared, TDD with MockEngine):** add to BrokerApi following its existing patterns exactly (decode() helper, @Serializable DTOs in-file):
  - `suspend fun listTerminals(session: String): TerminalList` → `GET $httpBase/api/term/list?session=<urlencoded>` → `@Serializable data class TerminalList(val terminals: List<TerminalInfo>)`, `@Serializable data class TerminalInfo(val id: String, val createdAt: Long? = null)` (verify the createdAt type against the broker handler `src/channels/web/index.ts:2324` — read it).
  - `suspend fun closeTerminal(session: String, terminal: String)` → `POST $httpBase/api/term/close` JSON body `{session, terminal}`.
  MockEngine tests assert exact method/path/query/body like the neighboring BrokerApi tests do. Run `:shared:jvmTest` (should go 288→290+) AND `:android:compileDebugKotlin`.
- [ ] **Step 2 (desktop):** `DesktopAppState`: `fun connectTerminal(sessionId, terminalId) = TerminalClient(baseUrl, token, http, sessionId, terminalId = terminalId)`, `fun connectAgentTerminal(sessionId) = TerminalClient(baseUrl, token, http, sessionId, kind = "agent")` (Android parity, AppViewModel:439-444) + suspend wrappers `listTerminals`/`closeTerminal` over `api` with the usual runCatching+log.
- [ ] **Step 3:** `TerminalTabs.kt`: web-parity tab strip above the terminal (per-session): hydrate from `listTerminals` on first show, `+` adds a tab (new id: short random suffix like web), close (`×` on hover / middle-click) calls `closeTerminal` + removes, one `DesktopTerminalPanel` per tab with only the ACTIVE tab's panel visible (keep-alive semantics from Task 3 for the inactive ones — bounded: keep at most the active + last-active connected, disconnect others; document). Tab bar styling: lean, mono ids, matches the workspace header language.
- [ ] **Step 4:** Wire `TerminalTabs` in as `SessionDetail`'s `terminalPane` lambda (replace `ComingSoonPane("Terminal", "M2", …)`).
- [ ] **Step 5:** Suites green; commit `feat(desktop): scratch terminal with web-parity tabs (shared list/close endpoints)`.

---

### Task 7: Agent terminal — Chat|Native toggle

**Files:**
- Create: `apps/desktop/src/main/kotlin/dev/supermux/desktop/workspace/AgentViewToggle.kt` (port of Android's)
- Modify: `apps/desktop/src/main/kotlin/dev/supermux/desktop/workspace/SessionDetail.kt`

- [ ] **Step 1:** Port Android's `AgentViewToggle` (the Chat|Native pill; shown only for `session.agent == "claude"` — ChatScreen.kt:508 precedent). Remove the corresponding TODO(M4) line (this plan pulls the toggle forward into M2 because it's terminal UX; the REST of the M4 header chrome stays deferred — note this in the commit message).
- [ ] **Step 2:** In `SessionDetail`, the CHAT SLOT swaps content Chat↔Native exactly like Android's `chatOrNative` (the keep-alive overlay pattern — chat must NOT remount when toggling back; verify against the split-slot discipline). Native = `DesktopTerminalPanel(connect = { app.connectAgentTerminal(session.id) }, onExit = { /* flip back to Chat */ })`. Persist the choice per session via the layout's existing `nativeView`/`setNativeView` map (already ported + persisted in M1, currently unused).
- [ ] **Step 3:** UI test: toggle renders for a claude session and swaps testTags without losing the chat node from the tree (keep-alive), mirroring `SessionDetailTest` patterns.
- [ ] **Step 4:** Suites green; commit `feat(desktop): agent PTY view — Chat|Native toggle (layout.nativeView wired)`.

---

### Task 8: Milestone verification pass (user-mandated) + report

- [ ] **Step 1:** Add `SM_TERM_INPUT` env hook (Main.kt, same pattern/documentation as SM_SMOKE_SEND): format `<session-name>:<text>` — after snapshot + a 5s settle, opens/ensures the SCRATCH terminal client for that session and `sendInput(text.toByteArray())`. It types into the scratch shell ONLY (never kind=agent) — enforce in code, not just docs.
- [ ] **Step 2:** Live checklist (screenshots to /home/ahmet/.cache/m2-<n>-<slug>.png, live broker, throwaway session — reuse talebe-dummy-excel):
  1. `SM_PANES=t` + selected throwaway session → terminal pane shows a REAL shell prompt (tmux scratch "main") in Geist Mono on the branded bg.
  2. `SM_TERM_INPUT='talebe-dummy-excel:echo m2-terminal-roundtrip && ls\n'` → screenshot shows the command echoed AND its output rendered (full input round-trip through JediTerm→WS→tmux→WS→JediTerm).
  3. Tab strip: hydrated tabs visible; `+` and `×` verified via UI tests (no pointer) — screenshot shows the strip; note the substitution.
  4. Agent terminal: select an IDLE claude session (talebe-dummy-excel), Native toggle ON via a one-off `SM_NATIVE=1` style hook OR by persisting `nativeView` in ui-state.json before launch (pick the cheaper; document) → screenshot shows the live Claude TUI (the transcript view). DO NOT send input to it.
  5. Toggle terminal pane off/on (SM_PANES rerun or persisted panes) → tmux content resumes (backpressure gives the current grid) — screenshot after re-show.
  6. Suites: `:desktop:test` (74+new), `:shared:jvmTest` (290+), `:android:compileDebugKotlin` — all green.
  7. Predictive echo: engine+adapter covered by tests (26 shared + new adapter tests); the visual dim-echo needs a slow link (>40ms RTT) which localhost can't produce — note honestly as test-verified, feel-test deferred to a real remote link (same status as Android/iOS).
- [ ] **Step 3:** Fix what the checklist catches (own commits). Update THIS plan file's checkboxes, commit `docs(desktop): M2 plan executed`.
- [ ] **Step 4:** Report: per-item PASS/FAIL + screenshots, fixes, suite totals, substitutions, and what M3 (editor/KCEF) should know.

---

## Self-review notes (spec coverage for M2)

Spec M2 items: JediTerm terminal ✓ (T1-3), raw byte-stream over existing WS ✓ (T2), input/keys/paste native→WS ✓ (T2 write path), wheel→SGR via shared TerminalScroll ✓ (T4), predictive echo via shared engine + desktop adapter with acceptance = the shared parity behavior ✓ (T5), agent + scratch kinds ✓ (T6-7); web-parity scratch TABS added per the "full parity v1" scope decision (web is the parity reference; Android lacks tabs — desktop should not). Fallback (xterm-in-KCEF) NOT triggered — JediTerm's public buffer/cursor APIs remove the Android reflection problem entirely. Known limitations recorded: prediction visually gated to >40ms RTT (untestable on localhost), typeahead-off must hold, SwingPanel z-order constraints handled by the keep-alive strategy chosen in T3.
