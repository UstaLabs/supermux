# Windows/Linux Desktop Client — Milestone 4g-1 (Markdown Preview Toggle) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** On a `.md`/`.markdown` editor tab, a toolbar eye/pencil toggle that swaps the CodeMirror editor for a rendered-markdown overlay (and back). Purely client-side — no broker call, no KCEF/bridge change.

**Architecture:** Mirrors Android `EditorScreen.kt`. A single `previewMode: Boolean` on `EditorState` (not per-tab). The toolbar shows the toggle only on markdown tabs; when on, an opaque native Compose overlay (`dev.supermux.desktop.chat.MarkdownBody`, already used by chat) is painted over the still-warm `EditorSurface` (KCEF stays alive underneath). Re-render is automatic via Compose recomposition on `activeTab.content` change — no explicit trigger. This is the first of the M4g editor-deferral sub-milestones (preview → diff → LSP); it deliberately does NOT introduce `showDiff` (that's M4g-2), so the preview gate omits the `&& !showDiff` clause Android has — M4g-2 will add it.

**Tech Stack:** Compose Desktop, the existing `MarkdownBody` composable (`apps/desktop/.../chat/Timeline.kt`), `EditorState`, `EditorPanel`.

---

## Ground rules

All prior-milestone rules hold (standard gradle invocation with /home/ahmet/.cache logs + TMPDIR; Xvfb :77 + `SKIKO_RENDER_API=SOFTWARE`; paired config; xwd+Pillow; NO xdotool — env hooks; never restart the broker; snake_case tests; commit trailer `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`; touch ONLY apps/desktop/src, NEVER build). Suite baseline at M4g-1 start: desktop 434 / shared jvmTest 292 / android compile green.

- Pure Compose port — NO broker call, NO ServerFrame, NO KCEF/bridge/engine change. If you find yourself editing `DesktopEditorEngine`/`KcefRuntime`/`EditorBridgeShims`, stop — this feature doesn't touch them.
- Reuse `dev.supermux.desktop.chat.MarkdownBody(text, modifier)` verbatim — do NOT write a new markdown renderer. It's the same one Android's preview uses.
- Icons: desktop uses `Icons.Filled.*` (not Android's painterResource). Use an available eye/pencil pair — `Icons.Filled.Visibility` (preview off → show "Preview") / `Icons.Filled.Edit` (preview on → show "Edit"). If those aren't in the desktop icon dependency (the file currently uses KeyboardArrowDown/FolderOpen/Check/Warning), pick the closest available pair and note it; the exact glyph is not load-bearing.

---

### Task 1: `previewMode` state + the `isMarkdownPath` helper (TDD the pure bit)

**Files:** Modify `apps/desktop/.../editor/EditorState.kt`; add `isMarkdownPath` in `apps/desktop/.../editor/EditorPanel.kt` (or a small shared spot) + test.

- [x] Add to `EditorState`: `var previewMode by mutableStateOf(false)` (single flag, not per-tab — matches Android `EditorState.kt:41`). Remove the corresponding "OMITTED — TODO(M4)" line from the `EditorState.kt` header comment for previewMode (leave the diff/LSP omissions — those are M4g-2/3).
- [x] Add a private `isMarkdownPath(path: String): Boolean = path.lowercase().let { it.endsWith(".md") || it.endsWith(".markdown") }` (verbatim from Android `EditorScreen.kt:582-583`). Put it file-private in `EditorPanel.kt`.
- [x] **Test (pure):** a `EditorPanelMarkdownTest.kt` (or add to an existing editor test) asserting `isMarkdownPath` — true for `"a.md"`, `"README.MD"`, `"x.markdown"`, `"/deep/path/notes.Markdown"`; false for `"a.txt"`, `"a.mdx"`, `""`, `"mdfile"` (no dot), `"a.md.bak"`. If `isMarkdownPath` must be non-private to test, make it internal + `@VisibleForTesting`-style (match how other pure editor helpers are exposed for test in this module).

### Task 2: Toolbar toggle + preview overlay (Compose)

**Files:** Modify `apps/desktop/.../editor/EditorPanel.kt`.

- [x] Near the top of the `EditorPanel` composable body (after `val activeTab = editor.activeTab`, ~line 193), compute: `val activeIsMarkdown = activeTab?.path?.let(::isMarkdownPath) == true`; `val showPreviewToggle = activeIsMarkdown`; `val showPreview = editor.previewMode && activeIsMarkdown`. (Android also `&& !editor.showDiff` — omitted here; M4g-2 adds it when `showDiff` exists.)
- [x] Replace the `// TODO(M4): markdown-preview toggle + "View changes" diff button` line at `EditorPanel.kt:227` with the preview toggle, leaving a `// TODO(M4g-2): "View changes" diff button` in its place:
```kotlin
if (showPreviewToggle) {
    IconButton(
        onClick = { editor.previewMode = !editor.previewMode },
        modifier = Modifier.pointerHoverIcon(PointerIcon.Hand).testTag("editor_preview_toggle"),
    ) {
        Icon(
            imageVector = if (editor.previewMode) Icons.Filled.Edit else Icons.Filled.Visibility,
            contentDescription = if (editor.previewMode) "Edit" else "Preview",
            tint = if (editor.previewMode) cs.primary else cs.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
    }
}
// TODO(M4g-2): "View changes" diff button (Android has it next to preview).
```
- [x] Add the preview overlay inside the surface `Box` (the one at `EditorPanel.kt:307` holding `EditorSurface` + the empty-state overlay), as a sibling drawn AFTER `EditorSurface` (so it paints on top), gated on `showPreview && activeTab != null`:
```kotlin
if (showPreview && activeTab != null) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Color(c.code))
            .verticalScroll(rememberScrollState())
            .padding(Space.lg)
            .testTag("editor_preview"),
    ) {
        dev.supermux.desktop.chat.MarkdownBody(activeTab.content)
    }
}
```
(`Color(c.code)` is the code-surface color already used by the empty-state overlay at line 334 — reuse the same `c` handle. Opaque background so the editor underneath is hidden while the KCEF surface stays alive/warm.)
- [x] Confirm imports: `Icons.Filled.Visibility`/`Edit`, `rememberScrollState`, `verticalScroll`, `MarkdownBody`. Compile.

### Task 3: UI tests + live verification + report

**Files:** `apps/desktop/.../editor/` test + the plan doc.

- [x] **UI tests** (createComposeRule, faked seams — look at existing editor tests for the EditorPanel harness; if EditorPanel is hard to host under `runComposeUiTest` because of KCEF, test at the smallest hostable seam — e.g. a thin composable wrapping just the toolbar+overlay logic driven by an `EditorState`, mirroring how other KCEF-adjacent bits are split for test in this module): with a `.md` active tab, `editor_preview_toggle` is shown; tapping it flips `editor.previewMode` and shows `editor_preview` with the rendered content; with a `.txt` active tab, `editor_preview_toggle` is NOT shown. If the full panel can't be hosted (KCEF), at minimum unit-test `isMarkdownPath` (Task 1) + the `showPreview`/`showPreviewToggle` derivation as a pure function, and document the KCEF-host limitation (same substitution class prior editor milestones used).
- [x] Run `:desktop:test` → all green (434 + new).
- [x] **Live verification** (Xvfb :77 + SKIKO_RENDER_API=SOFTWARE + paired config): add a `SM_EDITOR_PREVIEW=<session-name>|<md-file-path>` hook (Main.kt env-catalog, off by default) that opens the named session, opens the given markdown file in the editor, and flips `previewMode = true` — so the rendered preview can be screenshotted headlessly (no xdotool). Open a real `.md` from a throwaway/real session's workdir, screenshot the rendered preview to `/home/ahmet/.cache/m4g1v-shots/` (confirm markdown renders — headings/lists/code), then toggle back. Cleanup; reset ui-state if touched. If a hook proves overkill for such a small feature, a manual screenshot via an existing session is fine — but capture at least one screenshot of the rendered preview as evidence.
- [x] Suites green (`--rerun-tasks`): desktop / shared jvmTest / android compile. Tick the plan, commit `docs(desktop): M4g-1 plan executed` + a short report incl. what M4g-2 (DiffView) inherits.

## Self-review notes
Spec coverage: markdown-preview toggle = the smallest editor deferral, pure Compose, MarkdownBody reused. No broker/bridge/KCEF surface touched (explicitly out of scope). The one deviation from Android is omitting the `&& !showDiff` gate clauses (showDiff doesn't exist until M4g-2) — flagged so M4g-2 restores full parity. Risk: S. Only real gotcha is testing under KCEF constraints — handled by testing the pure derivation + documenting, the established pattern.

## Execution notes (post-implementation)
All 3 tasks landed as specced. One finding NOT anticipated by the plan: on desktop, while KCEF is the ACTIVE surface, `EditorSurface` hosts it via a heavyweight AWT `SwingPanel` (`WebCodeEditor.kt`) — Compose Desktop paints heavyweight AWT children ABOVE all lightweight Compose siblings regardless of composition order, so the preview overlay (though correctly composed on top, and correctly `assertIsDisplayed()` in Compose UI tests) can be visually occluded by a live KCEF view. This is the SAME root cause already documented for the terminal panel (`DesktopTerminalPanel.kt:164-166`), not a new bug. The real fix, Compose's experimental `compose.interop.blending` system property, was prototyped and confirmed effective in principle, but (a) is GPU-only — no Software-renderer support on any platform (confirmed against JetBrains compose-multiplatform issue #4941), so it could not be verified under this environment's mandatory `SKIKO_RENDER_API=SOFTWARE`, and (b) would apply app-wide, including the terminal's own SwingPanel, which is outside this milestone's blast radius to land unverified — so it was deliberately left OUT of the shipped code (reverted from Main.kt) and instead documented at the overlay call-site in `EditorPanel.kt` with a TODO for a follow-up (M4g-2 or a dedicated infra task) to evaluate enabling it on a real GPU-accelerated run. Live-verification evidence was therefore captured via the KCEF-free `SMX_KCEF_FORCE_ERROR=1` native-fallback path (`NativeCodeEditor`, plain Compose, no SwingPanel), which cleanly proves the toggle/overlay/MarkdownBody rendering logic end-to-end (screenshot: `/home/ahmet/.cache/m4g1v-shots/m4g1v-e-final.png`).
