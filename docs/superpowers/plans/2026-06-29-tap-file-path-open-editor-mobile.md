# Tap-a-file-path-to-open-it-in-the-editor (iOS + Android) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Bring web's "tap a file path in an agent message → open it in the editor and scroll to the line" to native iOS (SwiftUI) and Android (Compose), at full parity.

**Architecture:** Path detection + workdir-relative resolution ported once to the **shared KMP module** (`dev.supermux.ui`), consumed by both apps. A LINK span is added to the shared markdown parser (Android) and a `.link`-attribute pass is added to iOS's `MarkdownView`. The only net-new editor capability — jump-to-line — is added once to the shared CodeMirror bridge (`cmRevealLine`) and threaded through each app's editor.

**Tech Stack:** Kotlin Multiplatform (`kotlin.test`, Gradle), Jetpack Compose (Compose BOM 2026.06.00 — `LinkAnnotation.Clickable`), SwiftUI + UIKit (`UITextViewDelegate`), CodeMirror 6 (`bun build`).

**Spec:** `docs/superpowers/specs/2026-06-29-tap-file-path-open-editor-mobile-design.md`

**Scope decision:** Linkify **agent (assistant) messages only** — the literal ask ("click a path on the response"). User-typed messages are left plain (a deliberate, minor deviation from web, which also links them). Detection covers paths in prose AND inline code (`` `src/foo.ts:42` ``), the common agent shape.

**Verification reality (state honestly in the final report):**
- Shared KMP tests run locally: `cd apps && ./gradlew :shared:jvmTest` (JDK 17 present). This is the authoritative parity check.
- Android compiles locally if the SDK is wired: `cd apps && ANDROID_HOME=$HOME/Android/Sdk ./gradlew :android:compileDebugKotlin` (SDK present at `~/Android/Sdk`).
- CodeMirror bundle rebuild runs locally (`bun`, web-app `node_modules`).
- **iOS cannot be built on this Linux host** — needs the remote Mac. Static review here; device/sim build + manual tap test on the Mac if available. No "works on iOS" claim without an actual build.

---

## File Structure

**Shared (KMP) — created/modified:**
- Create `apps/shared/src/commonMain/kotlin/dev/supermux/ui/FilePathRef.kt` — path regex, `parseFilePathRef`, `findFilePathRefs`, known-extension filter.
- Create `apps/shared/src/commonMain/kotlin/dev/supermux/ui/WorkdirPath.kt` — `toWorkdirRelativePath` (+ `normalizeWorkdirKey`), reusing `dev.supermux.session.inferHomeDir`.
- Modify `apps/shared/src/commonMain/kotlin/dev/supermux/ui/Markdown.kt` — add `SpanStyleKind.LINK`, `MdSpan.ref`, link-splitting in `parseInlineMarkdown`.
- Create `apps/shared/src/commonTest/kotlin/dev/supermux/ui/FilePathRefTest.kt`, `WorkdirPathTest.kt`, `MarkdownLinkTest.kt`.

**CodeMirror (shared by both apps):**
- Modify `apps/android/codemirror/cm6-entry.mjs` — add `window.cmRevealLine`.
- Regenerate `apps/android/src/main/assets/editor/cm6.js` and copy to `apps/iosApp/Supermux/EditorWeb/cm6.js`.

**Android — modified:**
- `apps/android/src/main/kotlin/dev/supermux/android/chat/Timeline.kt` — `mdAnnotated` LINK rendering; thread `onOpenFile` through `MarkdownBody`/`AssistantMessage`/`TimelineItemRow`.
- `apps/android/src/main/kotlin/dev/supermux/android/chat/ChatScreen.kt` — `onOpenFile` handler, path resolve, `Toast`, `pendingEditorOpen`, `activePanel = Editor`.
- `apps/android/src/main/kotlin/dev/supermux/android/editor/EditorEngine.kt` — `revealLine` + `cmRevealLine` flush.
- `apps/android/src/main/kotlin/dev/supermux/android/editor/EditorState.kt` — `EditorTab.revealLine` + `openFileAtLine`.
- `apps/android/src/main/kotlin/dev/supermux/android/editor/WebCodeEditor.kt` — `revealLine` param + effect.
- `apps/android/src/main/kotlin/dev/supermux/android/editor/EditorScreen.kt` — `EditorPanel` `pendingOpen` param; `revealFile` accepts a line; pass reveal to `WebCodeEditor`.

**iOS — modified:**
- `apps/iosApp/Supermux/Chat/MarkdownView.swift` — `.link` injection pass; `SelectableText` delegate; `onOpenFile`.
- `apps/iosApp/Supermux/Chat/ChatMessages.swift` — `MessageRow` gets `sessionId`/`workdir`; builds the `onOpenFile` closure.
- `apps/iosApp/Supermux/Chat/ChatPane.swift` — `SessionTranscript` passes `session.id`/`session.workdir` to `MessageRow`.
- `apps/iosApp/Supermux/Broker/BrokerSession.swift` — `openFileFromMessage`, `editorFocus`, `editorOpenError`.
- `apps/iosApp/Supermux/Editor/EditorState.swift` — `RevealRequest`, `openFileAtLine`.
- `apps/iosApp/Supermux/Editor/EditorWebView.swift` — `revealLine` props + Coordinator `revealLine` + flush on ready.
- `apps/iosApp/Supermux/Editor/EditorPane.swift` — pass `state.reveal` into `EditorWebView`.
- `apps/iosApp/Supermux/Chat/ChatView.swift` — observe `broker.editorFocus` → `tab = .editor`; `broker.editorOpenError` → banner.
- `apps/iosApp/Supermux/Shell/IPadWorkspace.swift` — observe `broker.editorFocus` → open editor pane.

---

## PHASE A — Shared KMP (the testable core)

### Task 1: `FilePathRef` — detection + parse (shared, TDD)

**Files:**
- Create: `apps/shared/src/commonMain/kotlin/dev/supermux/ui/FilePathRef.kt`
- Test: `apps/shared/src/commonTest/kotlin/dev/supermux/ui/FilePathRefTest.kt`

This is a faithful port of `src/web-app/src/lib/file-path-ref.ts` + the `hasKnownExtension`/`linkifyFilePaths` logic from `src/web-app/src/lib/markdown.ts`. All assertions below were verified against the live web regex.

- [ ] **Step 1: Write the failing test**

```kotlin
// apps/shared/src/commonTest/kotlin/dev/supermux/ui/FilePathRefTest.kt
package dev.supermux.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FilePathRefTest {
    // ── parseFilePathRef (anchored) — ports file-path-ref.test.ts ──
    @Test fun parses_single_line() =
        assertEquals(FilePathRef("src/main.ts", 105), parseFilePathRef("src/main.ts:105"))

    @Test fun parses_range() =
        assertEquals(FilePathRef("src/utils.ts", 10, 20), parseFilePathRef("src/utils.ts:10-20"))

    @Test fun parses_bare() =
        assertEquals(FilePathRef("src/main.ts"), parseFilePathRef("src/main.ts"))

    @Test fun parses_absolute_with_line() =
        assertEquals(
            FilePathRef("/home/user/projects/app/src/foo.ts", 42),
            parseFilePathRef("/home/user/projects/app/src/foo.ts:42"),
        )

    @Test fun parses_home_with_range() =
        assertEquals(
            FilePathRef("~/projects/app/src/foo.ts", 5, 15),
            parseFilePathRef("~/projects/app/src/foo.ts:5-15"),
        )

    @Test fun rejects_non_numeric_suffix() = assertNull(parseFilePathRef("src/file.ts:abc"))
    @Test fun rejects_inverted_range() = assertNull(parseFilePathRef("src/file.ts:20-10"))

    // ── findFilePathRefs (in-text) — ports linkifyFilePaths semantics ──
    @Test fun finds_path_mid_sentence() {
        val m = findFilePathRefs("see src/main.ts:105 now")
        assertEquals(1, m.size)
        assertEquals(FilePathRef("src/main.ts", 105), m[0].ref)
        assertEquals("src/main.ts:105", m[0].display)
    }

    @Test fun inverted_range_is_not_linkified() =
        assertEquals(emptyList(), findFilePathRefs("src/foo.ts:20-10"))

    @Test fun non_numeric_suffix_links_path_only() {
        val m = findFilePathRefs("src/file.ts:abc")
        assertEquals(1, m.size)
        assertEquals(FilePathRef("src/file.ts"), m[0].ref)
        assertEquals("src/file.ts", m[0].display) // ":abc" left out of the match
    }

    @Test fun unknown_extension_skipped() =
        assertEquals(emptyList(), findFilePathRefs("assets/logo.png"))

    @Test fun bare_filename_without_dir_skipped() =
        assertEquals(emptyList(), findFilePathRefs("file.ts:42"))

    @Test fun home_path_detected() {
        val m = findFilePathRefs("open ~/p/app/a.kt please")
        assertEquals(1, m.size)
        assertEquals(FilePathRef("~/p/app/a.kt"), m[0].ref)
    }

    @Test fun multiple_in_one_run() {
        val m = findFilePathRefs("a/b.ts and c/d.kt")
        assertEquals(listOf("a/b.ts", "c/d.kt"), m.map { it.ref.path })
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd apps && ./gradlew :shared:jvmTest --tests "dev.supermux.ui.FilePathRefTest"`
Expected: FAIL — `FilePathRef` / `parseFilePathRef` / `findFilePathRefs` unresolved.

- [ ] **Step 3: Write the implementation**

```kotlin
// apps/shared/src/commonMain/kotlin/dev/supermux/ui/FilePathRef.kt
package dev.supermux.ui

data class FilePathRef(val path: String, val line: Int? = null, val endLine: Int? = null)

/** One in-text match: half-open [start, end) char range + parsed ref + matched text. */
data class FilePathMatch(val start: Int, val end: Int, val ref: FilePathRef, val display: String)

/** Path body shared with linkification (relative, absolute, home-relative). Port of FILE_PATH_BODY. */
const val FILE_PATH_BODY: String =
    """(?:\.{0,2}/)?(?:[\w@.-]+/)+[\w.-]+\.[\w]+|(?:/|~/)(?:[\w@.-]+/)+[\w.-]+\.[\w]+"""

private val FILE_PATH_REF_RE = Regex("""^($FILE_PATH_BODY)(?::(.*))?$""")

/** Path + optional line suffix, with word boundaries. Port of FILE_PATH_MATCH_RE. */
val FILE_PATH_MATCH_RE = Regex("""(?<!\w)($FILE_PATH_BODY)(?::\d+(?:-\d+)?|:[^\s<>"'\w]+)?(?!\w)""")

/** Same 34-entry set as web's FILE_EXTENSIONS (markdown.ts). */
private val FILE_EXTENSIONS = setOf(
    "ts", "tsx", "js", "jsx", "vue", "py", "json", "md", "css", "html",
    "yml", "yaml", "toml", "sql", "sh", "bash", "zsh", "go", "rs",
    "rb", "java", "kt", "swift", "c", "cpp", "h", "hpp", "txt",
    "env", "gitignore", "dockerfile", "xml", "svg", "lock",
)

fun hasKnownExtension(path: String): Boolean =
    FILE_EXTENSIONS.contains(path.substringAfterLast('.', "").lowercase())

/** Parse a whole path token (anchored). Returns null on a non-numeric or inverted suffix. */
fun parseFilePathRef(raw: String): FilePathRef? {
    val trimmed = raw.trim()
    val m = FILE_PATH_REF_RE.matchEntire(trimmed) ?: return null
    val path = m.groupValues[1]
    val suffix = m.groups[2]?.value ?: return FilePathRef(path)

    val lineMatch = Regex("""^(\d+)(?:-(\d+))?$""").matchEntire(suffix) ?: return null
    val line = lineMatch.groupValues[1].toInt()
    val endLine = lineMatch.groups[2]?.value?.toInt()
    if (endLine != null && line > endLine) return null
    return FilePathRef(path, line, endLine)
}

fun stripFilePathRefSuffix(raw: String): String {
    val ref = parseFilePathRef(raw.trim())
    if (ref != null) return ref.path
    return raw.trim().replace(Regex(""":(\d+)(?:-(\d+))?$"""), "")
}

fun formatFilePathRef(ref: FilePathRef): String = when {
    ref.line == null -> ref.path
    ref.endLine != null -> "${ref.path}:${ref.line}-${ref.endLine}"
    else -> "${ref.path}:${ref.line}"
}

/** All known-extension path refs in a plain text run (ports linkifyFilePaths minus HTML). */
fun findFilePathRefs(text: String): List<FilePathMatch> =
    FILE_PATH_MATCH_RE.findAll(text).mapNotNull { m ->
        val ref = parseFilePathRef(m.value) ?: return@mapNotNull null
        if (!hasKnownExtension(ref.path)) return@mapNotNull null
        FilePathMatch(m.range.first, m.range.last + 1, ref, m.value)
    }.toList()
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd apps && ./gradlew :shared:jvmTest --tests "dev.supermux.ui.FilePathRefTest"`
Expected: PASS (all 13).

- [ ] **Step 5: Commit**

```bash
git add apps/shared/src/commonMain/kotlin/dev/supermux/ui/FilePathRef.kt \
        apps/shared/src/commonTest/kotlin/dev/supermux/ui/FilePathRefTest.kt
git commit -m "feat(shared): file-path detection ported from web (FilePathRef)"
```

---

### Task 2: `toWorkdirRelativePath` (shared, TDD)

**Files:**
- Create: `apps/shared/src/commonMain/kotlin/dev/supermux/ui/WorkdirPath.kt`
- Test: `apps/shared/src/commonTest/kotlin/dev/supermux/ui/WorkdirPathTest.kt`

Port of `toWorkdirRelativePath` + `normalizeWorkdirKey` from `src/web-app/src/lib/workdir-display.ts`. Reuses `dev.supermux.session.inferHomeDir` (already present). Returns `null` when the path resolves outside the workdir (the "outside this project" signal).

- [ ] **Step 1: Write the failing test** (ports `workdir-display.test.ts`)

```kotlin
// apps/shared/src/commonTest/kotlin/dev/supermux/ui/WorkdirPathTest.kt
package dev.supermux.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WorkdirPathTest {
    private val workdir = "/home/user/projects/project-api"
    private val home = "/home/user"

    @Test fun relative_pass_through() {
        assertEquals("src/main.ts", toWorkdirRelativePath("src/main.ts", workdir, home))
        assertEquals("src/main.ts", toWorkdirRelativePath("./src/main.ts", workdir, home))
    }

    @Test fun strips_absolute_under_workdir() =
        assertEquals("src/main.ts", toWorkdirRelativePath("$workdir/src/main.ts", workdir, home))

    @Test fun strips_home_relative_under_workdir() =
        assertEquals(
            "src/main.ts",
            toWorkdirRelativePath("~/projects/project-api/src/main.ts", workdir, home),
        )

    @Test fun rejects_outside_workdir() =
        assertNull(toWorkdirRelativePath("/etc/passwd", workdir, home))

    @Test fun strips_single_line_suffix() =
        assertEquals("src/a.ts", toWorkdirRelativePath("src/a.ts:10", workdir, home))

    @Test fun strips_range_suffix_absolute() =
        assertEquals("src/a.ts", toWorkdirRelativePath("$workdir/src/a.ts:10-20", workdir, home))

    @Test fun infers_home_when_null() =
        assertEquals("src/a.ts", toWorkdirRelativePath("~/projects/project-api/src/a.ts", workdir, null))

    @Test fun workdir_root_itself() =
        assertEquals("", toWorkdirRelativePath(workdir, workdir, home))
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd apps && ./gradlew :shared:jvmTest --tests "dev.supermux.ui.WorkdirPathTest"`
Expected: FAIL — `toWorkdirRelativePath` unresolved.

- [ ] **Step 3: Write the implementation**

```kotlin
// apps/shared/src/commonMain/kotlin/dev/supermux/ui/WorkdirPath.kt
package dev.supermux.ui

import dev.supermux.session.inferHomeDir

/** Map an agent-mentioned path to a workdir-relative path for the editor API.
 *  Returns "" for the workdir root, or null when the path is outside the workdir.
 *  Port of toWorkdirRelativePath (workdir-display.ts). */
fun toWorkdirRelativePath(path: String, workdir: String, homeDir: String?): String? {
    val root = normalizeWorkdirKey(workdir, homeDir)
    val trimmed = stripFilePathRefSuffix(path.trim())

    if (!trimmed.startsWith("/") && !trimmed.startsWith("~/") && trimmed != "~") {
        return trimmed.removePrefix("./")
    }
    val abs = normalizeWorkdirKey(trimmed, homeDir)
    if (abs == root) return ""
    return if (abs.startsWith("$root/")) abs.substring(root.length + 1) else null
}

/** Port of normalizeWorkdirKey: expand ~, repair home-prefixed tilde, collapse // , drop trailing /. */
fun normalizeWorkdirKey(workdir: String, homeDir: String?): String {
    val trimmed = workdir.trim()
    val home = normalizeHomeDir(homeDir ?: inferHomeDir(trimmed))
    val expanded = when {
        home != null && trimmed == "~" -> home
        home != null && trimmed.startsWith("~/") -> "$home/${trimmed.substring(2)}"
        else -> expandHomePrefixedTilde(trimmed, home)
    }
    val normalized = expanded.replace(Regex("/+"), "/")
    return if (normalized.length > 1) normalized.trimEnd('/') else normalized
}

private fun expandHomePrefixedTilde(workdir: String, homeDir: String?): String {
    if (homeDir == null) return workdir
    val homeTilde = "$homeDir/~"
    if (workdir == homeTilde) return homeDir
    if (workdir.startsWith("$homeTilde/")) return "$homeDir/${workdir.substring(homeTilde.length + 1)}"
    return workdir
}

private fun normalizeHomeDir(homeDir: String?): String? {
    if (homeDir.isNullOrEmpty()) return null
    val normalized = homeDir.replace(Regex("/+"), "/")
    return if (normalized.length > 1) normalized.trimEnd('/') else normalized
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `cd apps && ./gradlew :shared:jvmTest --tests "dev.supermux.ui.WorkdirPathTest"`
Expected: PASS (all 8).

- [ ] **Step 5: Commit**

```bash
git add apps/shared/src/commonMain/kotlin/dev/supermux/ui/WorkdirPath.kt \
        apps/shared/src/commonTest/kotlin/dev/supermux/ui/WorkdirPathTest.kt
git commit -m "feat(shared): workdir-relative path resolver ported from web"
```

---

### Task 3: `Markdown.kt` — `LINK` span (shared, TDD)

**Files:**
- Modify: `apps/shared/src/commonMain/kotlin/dev/supermux/ui/Markdown.kt:3-4` (types) and `parseInlineMarkdown` (append link-splitting).
- Test: `apps/shared/src/commonTest/kotlin/dev/supermux/ui/MarkdownLinkTest.kt`

Existing `MarkdownTest.kt` cases stay green (path-free inputs unaffected). The change adds a post-pass: each emitted PLAIN/CODE span is scanned with `findFilePathRefs`; matched ranges become `LINK` spans carrying the `FilePathRef`.

- [ ] **Step 1: Write the failing test**

```kotlin
// apps/shared/src/commonTest/kotlin/dev/supermux/ui/MarkdownLinkTest.kt
package dev.supermux.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class MarkdownLinkTest {
    @Test fun links_bare_path_in_prose() {
        assertEquals(
            listOf(
                MdSpan("see ", SpanStyleKind.PLAIN),
                MdSpan("src/main.ts:42", SpanStyleKind.LINK, FilePathRef("src/main.ts", 42)),
                MdSpan(" now", SpanStyleKind.PLAIN),
            ),
            parseInlineMarkdown("see src/main.ts:42 now"),
        )
    }

    @Test fun links_path_inside_inline_code() {
        assertEquals(
            listOf(
                MdSpan("open ", SpanStyleKind.PLAIN),
                MdSpan("src/a.ts", SpanStyleKind.LINK, FilePathRef("src/a.ts")),
            ),
            parseInlineMarkdown("open `src/a.ts`"),
        )
    }

    @Test fun non_path_code_unchanged() {
        assertEquals(
            listOf(MdSpan("run ", SpanStyleKind.PLAIN), MdSpan("ls -la", SpanStyleKind.CODE)),
            parseInlineMarkdown("run `ls -la`"),
        )
    }

    @Test fun bold_without_path_unchanged() {
        assertEquals(
            listOf(
                MdSpan("hi ", SpanStyleKind.PLAIN),
                MdSpan("bold", SpanStyleKind.BOLD),
                MdSpan(" there", SpanStyleKind.PLAIN),
            ),
            parseInlineMarkdown("hi **bold** there"),
        )
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd apps && ./gradlew :shared:jvmTest --tests "dev.supermux.ui.MarkdownLinkTest"`
Expected: FAIL — `SpanStyleKind.LINK` unresolved / `MdSpan` arity.

- [ ] **Step 3: Edit the types** (`Markdown.kt:3-4`)

Replace:
```kotlin
enum class SpanStyleKind { PLAIN, BOLD, ITALIC, CODE }
data class MdSpan(val text: String, val kind: SpanStyleKind)
```
with:
```kotlin
enum class SpanStyleKind { PLAIN, BOLD, ITALIC, CODE, LINK }
data class MdSpan(val text: String, val kind: SpanStyleKind, val ref: FilePathRef? = null)
```

- [ ] **Step 4: Add link-splitting to `parseInlineMarkdown`**

The current function ends (`Markdown.kt:177-178`) with:
```kotlin
    flushPlain()
    return spans
}
```
Change the body so emitted spans pass through a splitter. Replace `spans.add(MdSpan(buf.toString(), SpanStyleKind.PLAIN))` inside `flushPlain` and the `CODE` emission so they route through a helper, and add the helper + final mapping. Concretely, after the `while` loop, replace `flushPlain(); return spans` with:

```kotlin
    flushPlain()
    return spans.flatMap { splitLinks(it) }
}

/** Split a PLAIN or CODE span into PLAIN/CODE + LINK sub-spans on detected file paths.
 *  Other kinds (BOLD/ITALIC/already-LINK) pass through unchanged. */
private fun splitLinks(span: MdSpan): List<MdSpan> {
    if (span.kind != SpanStyleKind.PLAIN && span.kind != SpanStyleKind.CODE) return listOf(span)
    val matches = findFilePathRefs(span.text)
    if (matches.isEmpty()) return listOf(span)
    val out = mutableListOf<MdSpan>()
    var cursor = 0
    for (m in matches) {
        if (m.start > cursor) out.add(MdSpan(span.text.substring(cursor, m.start), span.kind))
        out.add(MdSpan(span.text.substring(m.start, m.end), SpanStyleKind.LINK, m.ref))
        cursor = m.end
    }
    if (cursor < span.text.length) out.add(MdSpan(span.text.substring(cursor), span.kind))
    return out
}
```

- [ ] **Step 5: Run new + existing markdown tests**

Run: `cd apps && ./gradlew :shared:jvmTest --tests "dev.supermux.ui.MarkdownLinkTest" --tests "dev.supermux.ui.MarkdownTest"`
Expected: PASS (new 4 + existing unchanged).

- [ ] **Step 6: Full shared suite (no regressions)**

Run: `cd apps && ./gradlew :shared:jvmTest`
Expected: PASS (whole `:shared` jvm suite green).

- [ ] **Step 7: Commit**

```bash
git add apps/shared/src/commonMain/kotlin/dev/supermux/ui/Markdown.kt \
        apps/shared/src/commonTest/kotlin/dev/supermux/ui/MarkdownLinkTest.kt
git commit -m "feat(shared): LINK span — linkify file paths in inline markdown"
```

---

## PHASE B — CodeMirror bridge (shared by both apps)

### Task 4: `cmRevealLine` + rebuild bundle

**Files:**
- Modify: `apps/android/codemirror/cm6-entry.mjs` (add after `cmSetScrollTop`, ~line 123).
- Regenerate: `apps/android/src/main/assets/editor/cm6.js`.
- Copy to: `apps/iosApp/Supermux/EditorWeb/cm6.js`.

- [ ] **Step 1: Add the function** after `window.cmSetScrollTop` (`cm6-entry.mjs:120-123`)

```javascript
// 1-indexed line; endLine<=0 or absent → caret only. Reveals centered.
window.cmRevealLine = function (line, endLine) {
  if (!view) return
  const doc = view.state.doc
  const ln = Math.max(1, Math.min(line || 1, doc.lines))
  const from = doc.line(ln).from
  const sel = (endLine && endLine > ln)
    ? { anchor: from, head: doc.line(Math.min(endLine, doc.lines)).to }
    : { anchor: from }
  view.dispatch({ selection: sel, effects: EditorView.scrollIntoView(from, { y: "center" }) })
  view.focus()
}
```

`EditorView` is already imported in this file (used by `cmInit`/themes), so `EditorView.scrollIntoView` resolves with no new import.

- [ ] **Step 2: Rebuild the bundle** (per `apps/android/codemirror/README.md`)

Run:
```bash
cd "$(git rev-parse --show-toplevel)"
[ -d src/web-app/node_modules ] || (cd src/web-app && bun install)
mkdir -p /tmp/cmbuild && ln -sfn "$PWD/src/web-app/node_modules" /tmp/cmbuild/node_modules
cp apps/android/codemirror/cm6-entry.mjs /tmp/cmbuild/
bun build /tmp/cmbuild/cm6-entry.mjs \
  --outfile apps/android/src/main/assets/editor/cm6.js \
  --target browser --format iife --minify
```
Expected: a regenerated `cm6.js` (no build errors).

- [ ] **Step 3: Verify the global is present in the built bundle**

Run: `grep -c "cmRevealLine" apps/android/src/main/assets/editor/cm6.js`
Expected: `≥ 1`.

- [ ] **Step 4: Copy the bundle to iOS**

Run: `cp apps/android/src/main/assets/editor/cm6.js apps/iosApp/Supermux/EditorWeb/cm6.js`
Then: `grep -c "cmRevealLine" apps/iosApp/Supermux/EditorWeb/cm6.js` → expect `≥ 1`.

- [ ] **Step 5: Commit**

```bash
git add apps/android/codemirror/cm6-entry.mjs \
        apps/android/src/main/assets/editor/cm6.js \
        apps/iosApp/Supermux/EditorWeb/cm6.js
git commit -m "feat(editor): cmRevealLine — scroll CodeMirror to a 1-indexed line/range"
```

---

## PHASE C — Android

### Task 5: Tappable LINK spans in messages

**Files:**
- Modify: `apps/android/.../chat/Timeline.kt` — `mdAnnotated` (163-182), `MarkdownBody` (272-349), `AssistantMessage` (256-264), `TimelineItemRow` (606-635).

Render a `LINK` span via `withLink(LinkAnnotation.Clickable(...))` (Compose BOM 2026.06.00 — stable). Thread an `onOpenFile: (FilePathRef) -> Unit` down to `mdAnnotated`. Only `AssistantMessage` passes a real callback; `UserMessage` keeps the default no-op (so user messages stay plain).

- [ ] **Step 1: Add imports** at the top of `Timeline.kt` (near the existing `androidx.compose.ui.text` imports)

```kotlin
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.style.TextDecoration
import dev.supermux.ui.FilePathRef
```

- [ ] **Step 2: Rewrite `mdAnnotated`** (`Timeline.kt:163-182`) to take `onOpenFile` and render LINK

```kotlin
@Composable
fun mdAnnotated(
    text: String,
    onOpenFile: (FilePathRef) -> Unit = {},
): AnnotatedString {
    val linkColor = MaterialTheme.colorScheme.primary
    return buildAnnotatedString {
        text.split("\n").forEachIndexed { i, line ->
            if (i > 0) append("\n")
            for (s in parseInlineMarkdown(line)) {
                when (s.kind) {
                    SpanStyleKind.BOLD -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(s.text) }
                    SpanStyleKind.ITALIC -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(s.text) }
                    SpanStyleKind.CODE -> withStyle(
                        SpanStyle(fontFamily = MonoFontFamily, fontSize = 12.sp, fontWeight = FontWeight.Normal)
                    ) { append(s.text) }
                    SpanStyleKind.LINK -> {
                        val ref = s.ref
                        if (ref == null) append(s.text) else withLink(
                            LinkAnnotation.Clickable(
                                tag = "file:${ref.path}",
                                styles = TextLinkStyles(
                                    style = SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)
                                ),
                            ) { onOpenFile(ref) }
                        ) { append(s.text) }
                    }
                    SpanStyleKind.PLAIN -> append(s.text)
                }
            }
        }
    }
}
```

- [ ] **Step 3: Thread `onOpenFile` through `MarkdownBody`**

Change the signature (`Timeline.kt:272`) to:
```kotlin
@Composable
fun MarkdownBody(text: String, modifier: Modifier = Modifier, onOpenFile: (FilePathRef) -> Unit = {}) {
```
and pass `onOpenFile` into **every** `mdAnnotated(...)` call inside it (Prose, Heading, Quote, Bullet, Numbered) — e.g. `text = mdAnnotated(block.text, onOpenFile)`.

- [ ] **Step 4: Thread through `AssistantMessage`** (`Timeline.kt:256-264`)

```kotlin
@Composable
fun AssistantMessage(text: String, onOpenFile: (FilePathRef) -> Unit = {}) {
    MarkdownBody(
        text = text,
        modifier = Modifier.fillMaxWidth().padding(vertical = Space.xs),
        onOpenFile = onOpenFile,
    )
}
```
(`UserMessage` is unchanged — its `mdAnnotated(text)` keeps the default no-op, so user messages render plain.)

- [ ] **Step 5: Thread through `TimelineItemRow`** (`Timeline.kt:606-635`)

Add the param and pass it to `AssistantMessage`:
```kotlin
@Composable
fun TimelineItemRow(
    item: TimelineItem,
    loadBytes: suspend (String) -> ByteArray? = { null },
    onOpenFile: (FilePathRef) -> Unit = {},
) {
    // ... unchanged until the Msg arm:
    if (isUser) UserMessage(text) else AssistantMessage(text, onOpenFile)
    // ... rest unchanged
}
```

- [ ] **Step 6: Compile-check** (the call site in ChatScreen still uses the default arg, so it compiles)

Run: `cd apps && ANDROID_HOME=$HOME/Android/Sdk ./gradlew :android:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add apps/android/src/main/kotlin/dev/supermux/android/chat/Timeline.kt
git commit -m "feat(android): render file paths in messages as tappable links"
```

---

### Task 6: ChatScreen routes the tap → editor

**Files:**
- Modify: `apps/android/.../chat/ChatScreen.kt` — timeline call site (713-731), `EditorPanel` call (1183-1207); add state + handler near `activePanel` (334).

- [ ] **Step 1: Add imports** to `ChatScreen.kt`

```kotlin
import android.widget.Toast
import dev.supermux.ui.FilePathRef
import dev.supermux.ui.toWorkdirRelativePath
import dev.supermux.session.inferHomeDir
import dev.supermux.android.editor.PendingEditorOpen
```

- [ ] **Step 2: Add the pending-open state + handler** near `activePanel` (`ChatScreen.kt:334`). `context` (`:213`) and `session` are already in scope.

```kotlin
var pendingEditorOpen by remember(session.id) { mutableStateOf<PendingEditorOpen?>(null) }
val onOpenFile: (FilePathRef) -> Unit = remember(session.id) {
    { ref ->
        val rel = toWorkdirRelativePath(ref.path, session.workdir, inferHomeDir(session.workdir))
        if (rel == null) {
            Toast.makeText(context, "File is outside this session's project", Toast.LENGTH_SHORT).show()
        } else {
            pendingEditorOpen = PendingEditorOpen(rel, ref.line, ref.endLine)
            activePanel = SessionPanel.Editor
        }
    }
}
```

- [ ] **Step 3: Pass `onOpenFile` into the timeline** (`ChatScreen.kt:728`)

```kotlin
                items(timelineItems, key = { timelineItemKey(it) }) { item ->
                    TimelineItemRow(item, loadBytes, onOpenFile)
                }
```

- [ ] **Step 4: Pass the pending open into `EditorPanel`** (`ChatScreen.kt:1183-1207`) — add two args before `modifier`:

```kotlin
                    pendingOpen = pendingEditorOpen,
                    onPendingOpenConsumed = { pendingEditorOpen = null },
                    modifier = Modifier.keepAlivePanel(activePanel == SessionPanel.Editor),
```

- [ ] **Step 5: Compile-check** (fails until Task 8 adds the `EditorPanel` params — expected; do Task 7+8 next, then compile). Mark this step done after Tasks 7–8.

- [ ] **Step 6: Commit** (together with Tasks 7–8 once it compiles)

```bash
git add apps/android/src/main/kotlin/dev/supermux/android/chat/ChatScreen.kt
git commit -m "feat(android): route a tapped path to open the editor pane"
```

---

### Task 7: `EditorEngine.revealLine` → `cmRevealLine`

**Files:**
- Modify: `apps/android/.../editor/EditorEngine.kt` — add a pending-reveal field, `revealLine`, and flush in `pushToView` + `onReady`.

- [ ] **Step 1: Add the pending-reveal field** next to `private var lastScrollTop = 0` (`EditorEngine.kt:69`)

```kotlin
    private var pendingReveal: Pair<Int, Int?>? = null
```

- [ ] **Step 2: Add the method + flush helper** (after `setDocument`, ~`EditorEngine.kt:84`)

```kotlin
    /** Scroll to a 1-indexed line (optional end). Deferred until [ready], like scrollTop. */
    fun revealLine(line: Int, endLine: Int?) {
        pendingReveal = line to endLine
        if (ready) flushReveal()
    }

    private fun flushReveal() {
        val r = pendingReveal ?: return
        pendingReveal = null
        webView?.evaluateJavascript("cmRevealLine(${r.first}, ${r.second ?: -1})", null)
    }
```

- [ ] **Step 3: Flush after content push** — at the end of `pushToView` (`EditorEngine.kt:171-178`), after `cmSetScrollTop`:

```kotlin
        view.evaluateJavascript("cmSetScrollTop($scrollTop)", null)
        flushReveal()
    }
```

(The JS `onReady()` already calls `pushToView(lastContent, lastFilename, lastScrollTop)` at `:126`, so a reveal requested before ready is flushed there too.)

- [ ] **Step 4: Compile-check after Task 8** (needs the WebCodeEditor caller). Defer the run.

---

### Task 8: EditorState reveal + EditorPanel `pendingOpen` + WebCodeEditor

**Files:**
- Modify: `apps/android/.../editor/EditorState.kt` — `EditorTab.revealLine`, `EditorState.openFileAtLine`.
- Modify: `apps/android/.../editor/WebCodeEditor.kt` — `revealLine` param + effect.
- Modify: `apps/android/.../editor/EditorScreen.kt` — `PendingEditorOpen` type, `EditorPanel` params, `revealFile(line)`, WebCodeEditor call.

- [ ] **Step 1: `EditorTab` gains a reveal field** (`EditorState.kt:13-18`)

```kotlin
class EditorTab(path: String, content: String) {
    val path = path
    var content by mutableStateOf(content)
    var savedContent by mutableStateOf(content)
    var scrollTop by mutableStateOf(0)
    var revealLine by mutableStateOf<Pair<Int, Int?>?>(null)
}
```

- [ ] **Step 2: `EditorState.openFileAtLine`** (add after `openFile`, `EditorState.kt:83`)

```kotlin
    /** Open [path] and, once present, request a scroll to [line] (1-indexed). */
    fun openFileAtLine(path: String, line: Int?, endLine: Int?) {
        openFile(path)
        // openFile may add the tab synchronously (cache hit) or after fsRead; set on the tab
        // when it exists, else stash on a fresh open via a one-shot.
        val tab = tabs.find { it.path == path }
        if (line != null) {
            if (tab != null) tab.revealLine = line to endLine
            else scope.launch {
                // tab arrives after fsRead completes; poll the state list briefly.
                repeat(50) {
                    val t = tabs.find { it.path == path }
                    if (t != null) { t.revealLine = line to endLine; return@launch }
                    kotlinx.coroutines.delay(20)
                }
            }
        }
    }
```

- [ ] **Step 3: `WebCodeEditor` takes `revealLine`** (`WebCodeEditor.kt:28-41`)

Add the param and fire it in the document effect:
```kotlin
@Composable
fun WebCodeEditor(
    engine: EditorEngine,
    content: String,
    filename: String,
    fontSize: Int,
    scrollTop: Int = 0,
    revealLine: Pair<Int, Int?>? = null,
    onChange: (String) -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(content, filename, scrollTop, revealLine) {
        engine.setDocument(content, filename, scrollTop)
        revealLine?.let { engine.revealLine(it.first, it.second) }
    }
    // ... rest unchanged
```

- [ ] **Step 4: `EditorScreen.kt` — declare `PendingEditorOpen`** (top-level in the file, near other editor types)

```kotlin
/** A chat-initiated request to open a workdir-relative [path] at an optional [line]. */
data class PendingEditorOpen(val path: String, val line: Int?, val endLine: Int?)
```

- [ ] **Step 5: `EditorPanel` gains params** (`EditorScreen.kt:77-103`, add before `modifier`)

```kotlin
    pendingOpen: PendingEditorOpen? = null,
    onPendingOpenConsumed: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
```

- [ ] **Step 6: Make `revealFile` accept a line** (`EditorScreen.kt:200-207`)

```kotlin
    fun revealFile(path: String, line: Int? = null, endLine: Int? = null) {
        focusManager.clearFocus()
        engine.readScrollTop { scroll -> editor.captureActiveScroll(scroll) }
        editor.openFileAtLine(path, line, endLine)
        editor.searchQuery = ""
        searchResults.clear()
        if (!expanded) editor.treeVisible = false
    }
```
The existing tree/search callers (`onOpenFile = { revealFile(it) }`, `onSelect = { revealFile(it) }`) still compile (line defaults to null).

- [ ] **Step 7: Consume `pendingOpen`** — add a `LaunchedEffect` inside `EditorPanel` (after `revealFile` is defined)

```kotlin
    LaunchedEffect(pendingOpen) {
        pendingOpen?.let {
            revealFile(it.path, it.line, it.endLine)
            onPendingOpenConsumed()
        }
    }
```

- [ ] **Step 8: Pass the tab's reveal into `WebCodeEditor`** (`EditorScreen.kt:424-437`)

Add one argument:
```kotlin
                            WebCodeEditor(
                                engine = engine,
                                content = activeTab?.content ?: "",
                                filename = activeTab?.path ?: "",
                                fontSize = fontSize,
                                scrollTop = activeTab?.scrollTop ?: 0,
                                revealLine = activeTab?.revealLine,
                                onChange = { content -> activeTab?.path?.let { editor.updateContent(it, content) } },
                                onSave = { editor.saveActive() },
                                modifier = Modifier.fillMaxSize(),
                            )
```

- [ ] **Step 9: Compile the whole Android module** (Tasks 6–8 together)

Run: `cd apps && ANDROID_HOME=$HOME/Android/Sdk ./gradlew :android:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 10: Commit**

```bash
git add apps/android/src/main/kotlin/dev/supermux/android/editor/EditorEngine.kt \
        apps/android/src/main/kotlin/dev/supermux/android/editor/EditorState.kt \
        apps/android/src/main/kotlin/dev/supermux/android/editor/WebCodeEditor.kt \
        apps/android/src/main/kotlin/dev/supermux/android/editor/EditorScreen.kt
git commit -m "feat(android): open editor at a line from a tapped path (cmRevealLine)"
```

---

### Task 9: Android end-to-end build verification

- [ ] **Step 1: Assemble debug** (real, on this host)

Run: `cd apps && ANDROID_HOME=$HOME/Android/Sdk ./gradlew :android:assembleDebug`
Expected: BUILD SUCCESSFUL. If the SDK isn't usable, record that and fall back to `:android:compileDebugKotlin`.

- [ ] **Step 2: Manual smoke (if an emulator is available — see `mux:running-emulators`)**: open a session, have the agent cite `src/foo.ts:10`, tap it → editor opens at line 10; tap a `:5-9` range → range selected; tap `/etc/hosts` → toast. Record results; do not claim pass without observing it.

---

## PHASE D — iOS

### Task 10: Tappable paths in `MarkdownView`

**Files:**
- Modify: `apps/iosApp/Supermux/Chat/MarkdownView.swift` — `MarkdownView` props, `SelectableText` (+ Coordinator/delegate), a `.link` injection pass.

Add `onOpenFile: ((FilePathRef) -> Void)?` to `MarkdownView`; after building each block's `NSAttributedString`, scan its plain text with shared `findFilePathRefs` and set a `supermux-file://` `.link` (+ teal underline) on matched UTF-16 ranges; `SelectableText` gets a delegate that intercepts that scheme.

- [ ] **Step 1: Import Shared** at the top of `MarkdownView.swift` (if not already): `import Shared`.

- [ ] **Step 2: A helper to inject path links** into an `NSAttributedString` (add near `MarkdownAttributed`)

```swift
enum FilePathLinks {
    /// Custom scheme so the UITextView delegate can intercept (vs. opening a URL).
    static let scheme = "supermux-file"

    static func decorate(_ s: NSMutableAttributedString) {
        let plain = s.string as NSString
        for m in findFilePathRefs(text: s.string) {   // shared KMP
            // KMP returns UTF-16 (Kotlin String) indices; NSString is UTF-16 → align.
            let range = NSRange(location: Int(m.start), length: Int(m.end - m.start))
            guard range.location + range.length <= plain.length else { continue }
            guard let url = url(for: m.ref) else { continue }
            s.addAttributes([
                .link: url,
                .foregroundColor: UIColor(Theme.teal),
                .underlineStyle: NSUnderlineStyle.single.rawValue,
            ], range: range)
        }
    }

    static func url(for ref: FilePathRef) -> URL? {
        var c = URLComponents()
        c.scheme = scheme
        c.host = ""
        c.path = "/" + (ref.path.removingPercentEncoding ?? ref.path)
        var q: [URLQueryItem] = []
        if let line = ref.line { q.append(URLQueryItem(name: "line", value: "\(line.intValue)")) }
        if let end = ref.endLine { q.append(URLQueryItem(name: "end", value: "\(end.intValue)")) }
        c.queryItems = q.isEmpty ? nil : q
        return c.url
    }

    /// Parse a tapped supermux-file URL back into a FilePathRef.
    static func ref(from url: URL) -> FilePathRef? {
        guard url.scheme == scheme else { return nil }
        let path = String(url.path.dropFirst()) // strip leading "/"
        let items = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems
        let line = items?.first { $0.name == "line" }?.value.flatMap { Int($0) }
        let end = items?.first { $0.name == "end" }?.value.flatMap { Int($0) }
        return FilePathRef(path: path, line: line.map { KotlinInt(int: Int32($0)) },
                           endLine: end.map { KotlinInt(int: Int32($0)) })
    }
}
```
Note: `FilePathRef.line`/`endLine` bridge to `KotlinInt?` in Swift; `.intValue` reads it. (Confirm the exact bridged type at build time; SKIE/Kotlin-Native maps `Int?` → `KotlinInt?`.)

- [ ] **Step 3: Call `decorate` in `build(blocks:)`** (`MarkdownView.swift:73-85`)

Wrap each appended block string. Change `out.append(attributed(for: b))` to:
```swift
            let piece = NSMutableAttributedString(attributedString: attributed(for: b))
            FilePathLinks.decorate(piece)
            out.append(piece)
```

- [ ] **Step 4: `MarkdownView` takes `onOpenFile`** (`MarkdownView.swift:21-40`)

```swift
struct MarkdownView: View {
    let text: String
    var onOpenFile: ((FilePathRef) -> Void)? = nil
    // ... body: pass onOpenFile to SelectableText:
                    SelectableText(attributed: MarkdownAttributed.build(blocks: blocks), onOpenFile: onOpenFile)
```

- [ ] **Step 5: `SelectableText` gets a delegate** (`MarkdownView.swift:463-493`)

```swift
struct SelectableText: UIViewRepresentable {
    let attributed: NSAttributedString
    var onOpenFile: ((FilePathRef) -> Void)? = nil

    func makeCoordinator() -> Coordinator { Coordinator(onOpenFile: onOpenFile) }

    func makeUIView(context: Context) -> UITextView {
        let tv = UITextView()
        // ... all existing config unchanged ...
        tv.dataDetectorTypes = []
        tv.tintColor = UIColor(Theme.teal)
        tv.delegate = context.coordinator
        // ... unchanged ...
        return tv
    }

    func updateUIView(_ tv: UITextView, context: Context) {
        context.coordinator.onOpenFile = onOpenFile
        if !tv.attributedText.isEqual(attributed) { tv.attributedText = attributed }
    }

    // sizeThatFits unchanged

    final class Coordinator: NSObject, UITextViewDelegate {
        var onOpenFile: ((FilePathRef) -> Void)?
        init(onOpenFile: ((FilePathRef) -> Void)?) { self.onOpenFile = onOpenFile }

        func textView(_ tv: UITextView, shouldInteractWith URL: URL,
                      in characterRange: NSRange, interaction: UITextItemInteraction) -> Bool {
            if let ref = FilePathLinks.ref(from: URL) { onOpenFile?(ref); return false }
            return true   // non-file links keep default behavior
        }
    }
}
```

- [ ] **Step 6: Static review** (no iOS compiler on this host). Verify: scheme round-trips, ranges are UTF-16, delegate returns `false` only for our scheme. Commit.

```bash
git add apps/iosApp/Supermux/Chat/MarkdownView.swift
git commit -m "feat(ios): render file paths in messages as tappable links"
```

---

### Task 11: `MessageRow` builds the open-file closure

**Files:**
- Modify: `apps/iosApp/Supermux/Chat/ChatMessages.swift` — `MessageRow` gets `sessionId`/`workdir`, passes `onOpenFile` to `MarkdownView`.
- Modify: `apps/iosApp/Supermux/Chat/ChatPane.swift:406` — `SessionTranscript` passes `session.id`/`session.workdir`.

- [ ] **Step 1: `MessageRow` new props + closure** (`ChatMessages.swift:6-30`)

```swift
struct MessageRow: View {
    let entry: LogEntry
    let broker: BrokerSession
    let sessionId: String
    let workdir: String
    private var isAgent: Bool { entry.direction.hasPrefix("out") }

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            let text = entry.text ?? ""
            if !text.isEmpty {
                if isAgent {
                    MarkdownView(text: text, onOpenFile: { ref in
                        broker.openFileFromMessage(sessionId: sessionId, workdir: workdir, ref: ref)
                    })
                        .font(.subheadline)
                        .transcriptBody()
                } else {
                    Text(text).font(.subheadline.weight(.medium))
                        .textSelection(.enabled)
                        .userMessageSurface()
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
            }
            if let atts = entry.attachments, !atts.isEmpty {
                ForEach(atts, id: \.file_id) { AttachmentView(att: $0, broker: broker) }
            }
        }
    }
}
```

- [ ] **Step 2: Update the call site** (`ChatPane.swift:406`) — `SessionTranscript` has `session`:

```swift
                            case .message(let m): MessageRow(entry: m, broker: broker,
                                                             sessionId: session.id, workdir: session.workdir)
```
The `.equatable()` gate on `SessionTranscript` (keyed on `session.id`) is unaffected — `session.id`/`session.workdir` are stable for a given instance.

- [ ] **Step 3: Static review + commit** (compiles after Task 12 adds `openFileFromMessage`).

```bash
git add apps/iosApp/Supermux/Chat/ChatMessages.swift apps/iosApp/Supermux/Chat/ChatPane.swift
git commit -m "feat(ios): wire message taps to the broker open-file entry"
```

---

### Task 12: `BrokerSession.openFileFromMessage` + focus/error signals

**Files:**
- Modify: `apps/iosApp/Supermux/Broker/BrokerSession.swift` — add `EditorFocusRequest`, `editorFocus`, `editorOpenError`, `openFileFromMessage`.

- [ ] **Step 1: Add the focus type + observable signals** (near the editor section, ~`BrokerSession.swift:516`)

```swift
    /// A chat-initiated request to bring a session's editor to the front (nonce de-dupes).
    struct EditorFocusRequest: Equatable { let sessionId: String; let nonce: Int }
    var editorFocus: EditorFocusRequest?
    /// Transient "couldn't open" message surfaced by the chat container as a banner.
    var editorOpenError: String?
```
(`BrokerSession` is `@Observable`, so these are observable as-is.)

- [ ] **Step 2: Add the entry point**

```swift
    /// Resolve a tapped path against the session workdir, open it in that session's
    /// editor at the line, and ask the UI to bring the editor forward.
    func openFileFromMessage(sessionId: String, workdir: String, ref: FilePathRef) {
        let home = inferHomeDir(workdir: workdir)
        guard let rel = toWorkdirRelativePath(path: ref.path, workdir: workdir, homeDir: home) else {
            editorOpenError = "File is outside this session's project"
            return
        }
        editorState(for: sessionId).openFileAtLine(rel, line: ref.line?.intValue, endLine: ref.endLine?.intValue)
        editorFocus = EditorFocusRequest(sessionId: sessionId, nonce: (editorFocus?.nonce ?? 0) + 1)
    }
```

- [ ] **Step 3: Static review + commit** (compiles after Task 13 adds `openFileAtLine`).

```bash
git add apps/iosApp/Supermux/Broker/BrokerSession.swift
git commit -m "feat(ios): BrokerSession.openFileFromMessage + editor focus signal"
```

---

### Task 13: iOS editor reveal-line plumbing

**Files:**
- Modify: `apps/iosApp/Supermux/Editor/EditorState.swift` — `RevealRequest`, `openFileAtLine`.
- Modify: `apps/iosApp/Supermux/Editor/EditorWebView.swift` — `revealLine`/`revealEndLine` props, Coordinator `revealLine`, flush on ready.
- Modify: `apps/iosApp/Supermux/Editor/EditorPane.swift` — pass `state.reveal` to `EditorWebView`.

- [ ] **Step 1: `EditorState` reveal state** (`EditorState.swift` — add near `Tab`/properties)

```swift
    struct RevealRequest: Equatable { let path: String; let line: Int; let endLine: Int?; let nonce: Int }
    private(set) var reveal: RevealRequest?
    private var revealNonce = 0

    /// Open [path] then request a scroll to [line] once the tab is active.
    func openFileAtLine(_ path: String, line: Int?, endLine: Int?) {
        Task {
            await openFile(path)
            if let line { revealNonce += 1; reveal = RevealRequest(path: path, line: line, endLine: endLine, nonce: revealNonce) }
        }
    }
```

- [ ] **Step 2: `EditorWebView` reveal props + Coordinator** (`EditorWebView.swift`)

Add props:
```swift
    var revealLine: Int? = nil
    var revealEndLine: Int? = nil
    var revealNonce: Int = 0
```
In `updateUIView`, after the content push, apply the reveal when its nonce is new:
```swift
        if let line = revealLine, coordinator.lastRevealNonce != revealNonce {
            coordinator.lastRevealNonce = revealNonce
            coordinator.pendingReveal = (line, revealEndLine)
            if coordinator.ready { coordinator.flushReveal() }
        }
```
In `Coordinator` add:
```swift
        var lastRevealNonce = 0
        var pendingReveal: (Int, Int?)?
        func revealLine(_ line: Int, _ endLine: Int?) { evaluate("cmRevealLine(\(line), \(endLine ?? -1))") }
        func flushReveal() {
            guard let r = pendingReveal else { return }
            pendingReveal = nil
            revealLine(r.0, r.1)
        }
```
And in `pushCachedDocument()` (the on-ready flush), call `flushReveal()` as the last line so a reveal requested before ready still fires.

- [ ] **Step 3: `EditorPane` passes the reveal** (`EditorPane.swift:248-256`, the `EditorWebView(...)` call)

```swift
                EditorWebView(host: host, content: tab.content, path: tab.path,
                              lineWrap: settings.lineWrap, fontSize: settings.fontSize,
                              revealLine: state.reveal?.path == tab.path ? state.reveal?.line : nil,
                              revealEndLine: state.reveal?.path == tab.path ? state.reveal?.endLine : nil,
                              revealNonce: state.reveal?.nonce ?? 0,
                              onChange: { state.updateContent(tab.path, $0) },
                              onSave: { Task { await state.saveActive() } },
                              onMakeView: { webView = $0 },
                              onLspOut: { serverId, message in
                                  broker.lspBridge(for: session.id).rpcOut(serverId, message)
                              })
```

- [ ] **Step 4: Static review + commit.**

```bash
git add apps/iosApp/Supermux/Editor/EditorState.swift \
        apps/iosApp/Supermux/Editor/EditorWebView.swift \
        apps/iosApp/Supermux/Editor/EditorPane.swift
git commit -m "feat(ios): reveal a line in the editor after opening a tapped path"
```

---

### Task 14: Bring the editor forward on focus (iPhone + iPad)

**Files:**
- Modify: `apps/iosApp/Supermux/Chat/ChatView.swift` — observe `broker.editorFocus` → `tab = .editor`; `broker.editorOpenError` → banner.
- Modify: `apps/iosApp/Supermux/Shell/IPadWorkspace.swift` — observe `broker.editorFocus` → open the editor pane.

- [ ] **Step 1: ChatView observers** (`ChatView.swift` — add `.onChange` modifiers on the `TabView`)

```swift
        .onChange(of: broker.editorFocus) { _, f in
            if let f, f.sessionId == session.id { tab = .editor }
        }
        .onChange(of: broker.editorOpenError) { _, msg in
            if let msg { showBanner(msg); broker.editorOpenError = nil }
        }
```
(`showBanner` already exists in ChatView for git results; `banner` is `@State`.)

- [ ] **Step 2: iPad observer** (`IPadWorkspace.swift` — `WorkspaceDetail`, near the `liveDisplayId` `.onChange`)

```swift
        .onChange(of: broker.editorFocus) { _, f in
            guard let f, let s = session, f.sessionId == s.id else { return }
            var v = layout.panes(for: s.id)
            v.editorOpen = true
            layout.setPanes(v, for: s.id)
        }
```

- [ ] **Step 3: Static review + commit.**

```bash
git add apps/iosApp/Supermux/Chat/ChatView.swift apps/iosApp/Supermux/Shell/IPadWorkspace.swift
git commit -m "feat(ios): focus the editor pane when a path is tapped (iPhone + iPad)"
```

---

### Task 15: iOS build + manual verification (remote Mac)

- [ ] **Step 1:** If a remote Mac is available (see `mux:ios-simulator-on-remote-mac`), sync the **whole `apps/`** dir (per the build rule), build the `Supermux` scheme for the simulator, and run.
- [ ] **Step 2: Manual matrix on the sim/device:** agent cites `src/foo.ts:12` → tap → editor tab opens at line 12; `:5-9` → range selected; inline-code `` `src/foo.ts` `` → opens; `/etc/hosts` → "outside this project" banner; non-existent file → editor "Couldn't open file".
- [ ] **Step 3:** If no Mac is reachable, **state clearly in the final report that iOS was static-review-only and not built.**

---

## PHASE E — Final verification & wrap

- [ ] **Step 1: Shared suite green** — `cd apps && ./gradlew :shared:jvmTest` → PASS.
- [ ] **Step 2: Android compiles** — `cd apps && ANDROID_HOME=$HOME/Android/Sdk ./gradlew :android:compileDebugKotlin` (or `assembleDebug`) → SUCCESS, or recorded limitation.
- [ ] **Step 3: cm6 bundle** contains `cmRevealLine` in both asset copies.
- [ ] **Step 4: No-legacy-names / grep gate** — `git grep -nE "claudemux|agentmux|AGENTMUX_" -- apps/shared apps/android apps/iosApp` returns nothing new from this work.
- [ ] **Step 5: requesting-code-review** on the full diff, then **finishing-a-development-branch**.
- [ ] **Step 6: Honest status report** to the user: what was built, what ran green, and explicitly that iOS needs the remote Mac to build/verify if it wasn't reachable.

---

## Self-Review notes (author)

- **Spec coverage:** detection (T1), resolver (T2), LINK span (T3), cmRevealLine (T4), Android render+route+reveal (T5–T8), iOS render+route+reveal (T10–T14). Error handling (outside-project, missing file), inline-code paths, and inverted-range parity are covered by T1/T3 tests + T6/T12 handlers.
- **Type consistency:** `FilePathRef(path, line?, endLine?)`, `FilePathMatch(start,end,ref,display)`, `PendingEditorOpen(path,line,endLine)` (Android), `EditorFocusRequest(sessionId,nonce)` + `RevealRequest(path,line,endLine,nonce)` (iOS) are used consistently across tasks. Android reveal is carried as `Pair<Int,Int?>`; iOS as a `RevealRequest`.
- **Known build-time confirmations (call out, don't guess):** (a) the Kotlin `Int?` → Swift `KotlinInt?` bridge for `FilePathRef.line` (Task 10/12 use `.intValue`); (b) exact `androidx.compose.ui.text` import names for `LinkAnnotation`/`withLink` under BOM 2026.06.00; (c) the `openFileAtLine` tab-arrival timing on Android (the poll is a fallback — prefer hooking the `fsRead` success if a cleaner seam exists). Each is local and testable at compile time.
