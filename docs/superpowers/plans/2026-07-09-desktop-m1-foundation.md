# Windows/Linux Desktop Client — Milestone 1 (Foundation) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A runnable Compose Multiplatform Desktop app (`apps/desktop`) that pairs to the broker, shows the live session list, and supports full chat (read + send + agent state) in the desktop workspace shell — per `docs/superpowers/specs/2026-07-09-windows-linux-desktop-client-design.md`. Terminal/editor/display panes render placeholder cards (Milestones 2/3/5).

**Architecture:** New `:desktop` JVM module consuming `:shared`'s existing `jvm()` target (`BrokerApi`, `BrokerClient`, frames — all jvm-tested). UI screens are ports of `apps/android` composables (workspace layout, session list, timeline chat); a plain `DesktopAppState` class replaces Android's `AppViewModel` with the identical reducer. A new persistent `DesktopTokenStore` replaces the in-memory jvm `SecureTokenStore` actual.

**Tech Stack:** Kotlin 2.3.21, Compose Multiplatform **1.11.1** (`org.jetbrains.compose`) + `org.jetbrains.kotlin.plugin.compose` (versioned with Kotlin), ktor 3.5.0 CIO, kotlinx-coroutines-swing, JDK 17.

---

## Ground rules (read before Task 1)

- **Working dir:** this worktree (`git branch --show-current` → `mux/supermux-14`). Commit here; the Android + shared modules must stay untouched except where a task says otherwise.
- **Gradle on this shared box:** run builds solo, heap-capped, and NEVER write big output to /tmp (per-user quota → Bash returns "exit 1, no output"):

```bash
cd apps && TMPDIR=/home/ahmet/.cache/tmp ./gradlew --no-daemon -Dorg.gradle.jvmargs=-Xmx2048M <task> > /home/ahmet/.cache/desktop-build.log 2>&1; tail -20 /home/ahmet/.cache/desktop-build.log
```

  (`mkdir -p /home/ahmet/.cache/tmp` first. Always tail the log file, never rely on inline output.)
- **Run the app headless** (this box has Xvfb; check screenshots with the Read tool — it renders PNGs):

```bash
Xvfb :77 -screen 0 1600x1000x24 & sleep 1
cd apps && DISPLAY=:77 TMPDIR=/home/ahmet/.cache/tmp ./gradlew --no-daemon :desktop:run > /home/ahmet/.cache/desktop-run.log 2>&1 &
sleep 25   # first run compiles; later runs ~8s
import -display :77 -window root /home/ahmet/.cache/desktop-shot.png   # ImageMagick; fallback: xwd -display :77 -root -out /tmp/x.xwd && convert
```

  Kill with `pkill -f 'desktop:run'` and `pkill Xvfb` when done. If `import` is missing, do NOT install packages — use the `xwd`+`convert` fallback, and if that's missing too, report it instead of skipping the screenshot.
- **Live-broker verification** (Tasks 5 and 10): the live broker runs on this box at `ws://127.0.0.1:9898`. Mint a pairing token from the MAIN checkout (NOT this worktree — the live broker runs from `~/projects/supermux`):

```bash
cd ~/projects/supermux && bun run pair desktop-dev 2>&1 | tail -3   # prints https://…/pair?t=<token>
```

  If `bun run pair` doesn't exist under that name, run `cat package.json | grep -A2 '"pair'` to find the script; do NOT restart or reconfigure the broker (hard rule). Pairing adds a device — that is allowed. Use `SM_PAIR_BASE=ws://127.0.0.1:9898 SM_PAIR_TOKEN=<token>` env vars (Task 5 implements them) for repeatable dev runs.
- **iOS/Android/shared must stay green:** any task that touches `apps/shared` or `apps/android` (only Tasks 1 and 3 do, minimally) must run `:shared:jvmTest` and `:android:compileDebugKotlin` afterward.
- **Commit trailer:** end every commit with `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>` (project-approved AI trailer format).

---

### Task 1: Gradle module skeleton + hello window

**Files:**
- Modify: `apps/gradle/libs.versions.toml`
- Modify: `apps/settings.gradle.kts`
- Modify: `apps/build.gradle.kts`
- Create: `apps/desktop/build.gradle.kts`
- Create: `apps/desktop/src/main/kotlin/dev/supermux/desktop/Main.kt`

- [ ] **Step 1: Version catalog.** In `apps/gradle/libs.versions.toml` add to `[versions]`:

```toml
composeMultiplatform = "1.11.1"
jediterm = "3.73"
kcef = "2025.03.23"
```

(jediterm/kcef are declared now so Milestones 2-3 don't touch the catalog; they're unused in M1.) Add to `[libraries]`:

```toml
coroutines-swing = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-swing", version.ref = "coroutines" }
jediterm-core = { module = "org.jetbrains.jediterm:jediterm-core", version.ref = "jediterm" }
jediterm-ui = { module = "org.jetbrains.jediterm:jediterm-ui", version.ref = "jediterm" }
kcef = { module = "dev.datlag:kcef", version.ref = "kcef" }
```

Add to `[plugins]`:

```toml
kotlinJvm = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }
composeMultiplatform = { id = "org.jetbrains.compose", version.ref = "composeMultiplatform" }
composeCompiler = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
```

⚠️ Check first with `grep -n 'composeCompiler\|kotlin.plugin.compose' apps/gradle/libs.versions.toml apps/android/build.gradle.kts apps/build.gradle.kts` — if the Android module already aliases the compose-compiler plugin under another name, REUSE that alias everywhere instead of adding a duplicate id (duplicate plugin ids in a catalog fail the build).

- [ ] **Step 2: settings + root plugins.** In `apps/settings.gradle.kts` add `include(":desktop")` after `include(":android")`. In `apps/build.gradle.kts`, add to the `plugins {}` block (all `apply false`, matching the existing style):

```kotlin
    alias(libs.plugins.kotlinJvm) apply false
    alias(libs.plugins.composeMultiplatform) apply false
```

(Only add `composeCompiler` here if Step 1 created a NEW alias; if Android already declares it, it's already listed.)

- [ ] **Step 3: Module build file.** Create `apps/desktop/build.gradle.kts`:

```kotlin
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.serialization)
}

repositories {
    mavenCentral()
    google()
    maven("https://packages.jetbrains.team/maven/p/ij/intellij-dependencies") // JediTerm (M2)
    maven("https://jogamp.org/deployment/maven")                              // KCEF transitive (M3)
}

dependencies {
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation(libs.coroutines.swing)
    implementation(libs.serialization.json)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.websockets)

    testImplementation(libs.coroutines.test)
    testImplementation(kotlin("test"))
    @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
    testImplementation(compose.desktop.uiTestJUnit4)
    testImplementation(compose.desktop.currentOs)
}

kotlin { jvmToolchain(17) }

compose.desktop {
    application {
        mainClass = "dev.supermux.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Deb, TargetFormat.Msi, TargetFormat.AppImage)
            packageName = "supermux"
            packageVersion = "1.0.0"
            description = "supermux desktop"
            vendor = "UstaLabs"
        }
    }
}
```

Note: `TargetFormat.AppImage` = jpackage's runnable app **directory** (we tar it for the "tar.gz" deliverable in M6), not a Linux `.AppImage` file. If the `@OptIn` line trips the compiler, replace with `testImplementation(compose.desktop.uiTestJUnit4)` without the annotation and add `kotlin.compilerOptions.optIn.add("org.jetbrains.compose.ExperimentalComposeLibrary")` — whichever compiles.

- [ ] **Step 4: Hello window.** Create `apps/desktop/src/main/kotlin/dev/supermux/desktop/Main.kt`:

```kotlin
package dev.supermux.desktop

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "supermux",
        state = rememberWindowState(width = 1440.dp, height = 900.dp),
    ) {
        MaterialTheme { Surface { Text("supermux desktop — M1 scaffold") } }
    }
}
```

- [ ] **Step 5: Build + shared/android still green.**

```bash
cd apps && TMPDIR=/home/ahmet/.cache/tmp ./gradlew --no-daemon -Dorg.gradle.jvmargs=-Xmx2048M :desktop:compileKotlin :shared:jvmTest :android:compileDebugKotlin > /home/ahmet/.cache/desktop-build.log 2>&1; tail -5 /home/ahmet/.cache/desktop-build.log
```

Expected: `BUILD SUCCESSFUL`. (First run downloads CMP artifacts — allow minutes.)

- [ ] **Step 6: Run it headless + screenshot.** Use the Ground-rules Xvfb recipe. Read the PNG — expect a 1440×900 window titled supermux with the scaffold text.

- [ ] **Step 7: Commit.**

```bash
git add apps/gradle/libs.versions.toml apps/settings.gradle.kts apps/build.gradle.kts apps/desktop
git commit -m "feat(desktop): Compose Multiplatform Desktop module skeleton (:desktop)"
```

---

### Task 2: Persistent `DesktopTokenStore` (TDD)

The jvm `SecureTokenStore` actual in `:shared` is in-memory by design. The desktop app gets its own store class (do NOT modify the shared actual — other jvm consumers are tests that rely on in-memory).

**Files:**
- Create: `apps/desktop/src/main/kotlin/dev/supermux/desktop/auth/DesktopTokenStore.kt`
- Create: `apps/desktop/src/test/kotlin/dev/supermux/desktop/auth/DesktopTokenStoreTest.kt`

- [ ] **Step 1: Failing test.** Create the test file:

```kotlin
package dev.supermux.desktop.auth

import java.nio.file.Files
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopTokenStoreTest {
    private fun tempStore(): DesktopTokenStore =
        DesktopTokenStore(Files.createTempDirectory("smx-store").resolve("auth.json"))

    @Test fun starts_empty() {
        val s = tempStore()
        assertNull(s.load()); assertNull(s.loadBaseUrl())
    }

    @Test fun saves_and_reloads_across_instances() {
        val s = tempStore()
        s.saveBaseUrl("ws://127.0.0.1:9898"); s.save("tok123")
        val again = DesktopTokenStore(s.path)
        assertEquals("tok123", again.load())
        assertEquals("ws://127.0.0.1:9898", again.loadBaseUrl())
    }

    @Test fun clear_removes_both() {
        val s = tempStore()
        s.saveBaseUrl("ws://x:1"); s.save("t"); s.clear()
        val again = DesktopTokenStore(s.path)
        assertNull(again.load()); assertNull(again.loadBaseUrl())
    }

    @Test fun file_is_owner_only_on_posix() {
        val s = tempStore(); s.save("secret")
        val posix = runCatching { Files.getPosixFilePermissions(s.path) }.getOrNull() ?: return
        assertTrue(posix.all { it.name.startsWith("OWNER_") }, "perms were $posix")
    }

    @Test fun corrupt_file_reads_as_empty() {
        val s = tempStore()
        Files.createDirectories(s.path.parent); Files.writeString(s.path, "{not json")
        assertNull(s.load())
    }

    @Test fun default_path_is_under_config_dir() {
        val p = DesktopTokenStore.defaultPath()
        assertTrue(p.toString().contains("supermux"), "was $p")
        assertTrue(p.fileName.toString() == "auth.json")
    }
}
```

- [ ] **Step 2: Run it — must FAIL** (class doesn't exist):

```bash
cd apps && TMPDIR=/home/ahmet/.cache/tmp ./gradlew --no-daemon :desktop:test --tests 'dev.supermux.desktop.auth.*' > /home/ahmet/.cache/desktop-test.log 2>&1; tail -5 /home/ahmet/.cache/desktop-test.log
```

Expected: compilation failure `unresolved reference: DesktopTokenStore`.

- [ ] **Step 3: Implement.** Create `DesktopTokenStore.kt`:

```kotlin
package dev.supermux.desktop.auth

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission

/**
 * Persistent paired-broker credentials for the desktop app.
 * JSON file under the platform config dir, owner-only perms on POSIX.
 * (The :shared jvm SecureTokenStore actual is deliberately in-memory; this
 * class is the desktop app's real store.)
 */
class DesktopTokenStore(val path: Path = defaultPath()) {

    @Serializable
    private data class Blob(val token: String? = null, val baseUrl: String? = null)

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    private fun read(): Blob =
        runCatching { json.decodeFromString<Blob>(Files.readString(path)) }.getOrDefault(Blob())

    private fun write(blob: Blob) {
        Files.createDirectories(path.parent)
        Files.writeString(path, json.encodeToString(Blob.serializer(), blob))
        runCatching {
            Files.setPosixFilePermissions(
                path,
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            )
        } // no-op on Windows
    }

    fun save(token: String) = write(read().copy(token = token))
    fun load(): String? = read().token?.takeIf { it.isNotBlank() }
    fun saveBaseUrl(url: String) = write(read().copy(baseUrl = url))
    fun loadBaseUrl(): String? = read().baseUrl?.takeIf { it.isNotBlank() }
    fun clear() { runCatching { Files.deleteIfExists(path) } }

    companion object {
        fun defaultPath(): Path {
            val os = System.getProperty("os.name").lowercase()
            val base = when {
                os.contains("win") -> Path.of(System.getenv("APPDATA") ?: (System.getProperty("user.home") + "\\AppData\\Roaming"))
                else -> Path.of(System.getenv("XDG_CONFIG_HOME") ?: (System.getProperty("user.home") + "/.config"))
            }
            return base.resolve("supermux-desktop").resolve("auth.json")
        }
    }
}
```

- [ ] **Step 4: Tests pass.** Re-run the Step 2 command. Expected: `BUILD SUCCESSFUL`, 6 tests.

- [ ] **Step 5: Commit.**

```bash
git add apps/desktop/src
git commit -m "feat(desktop): persistent DesktopTokenStore (config-dir JSON, owner-only perms)"
```

---

### Task 3: Theme port (tokens, fonts, SupermuxTheme)

Port `apps/android/src/main/kotlin/dev/supermux/android/theme/` → `apps/desktop/src/main/kotlin/dev/supermux/desktop/theme/`. The shared color source (`dev.supermux.ui.supermuxDark()/supermuxLight()`, OKLCH math) is commonMain — reused as-is.

**Files:**
- Create: `apps/desktop/src/main/resources/fonts/` (6 ttf files copied from `apps/android/src/main/res/font/`)
- Create: `apps/desktop/src/main/kotlin/dev/supermux/desktop/theme/Type.kt`
- Create: `.../theme/Tokens.kt`, `.../theme/Motion.kt`, `.../theme/SemanticColors.kt`, `.../theme/SupermuxTheme.kt`, `.../theme/Haptics.kt`

- [ ] **Step 1: Copy fonts.**

```bash
mkdir -p apps/desktop/src/main/resources/fonts
cp apps/android/src/main/res/font/geist_regular.ttf apps/android/src/main/res/font/geist_medium.ttf \
   apps/android/src/main/res/font/geist_semibold.ttf apps/android/src/main/res/font/geist_bold.ttf \
   apps/android/src/main/res/font/geist_mono_regular.ttf apps/android/src/main/res/font/geist_mono_medium.ttf \
   apps/desktop/src/main/resources/fonts/
```

- [ ] **Step 2: Port `Type.kt`.** Read the Android `theme/Type.kt` first. Desktop version replaces `Font(R.font.geist_regular, …)` with the desktop resource loader; everything else (the `supermuxTypography()` scale) copies verbatim (change package to `dev.supermux.desktop.theme`):

```kotlin
// font loading section only — the rest of the file copies verbatim from Android
import androidx.compose.ui.text.platform.Font

val GeistFontFamily = FontFamily(
    Font(resource = "fonts/geist_regular.ttf", weight = FontWeight.Normal),
    Font(resource = "fonts/geist_medium.ttf", weight = FontWeight.Medium),
    Font(resource = "fonts/geist_semibold.ttf", weight = FontWeight.SemiBold),
    Font(resource = "fonts/geist_bold.ttf", weight = FontWeight.Bold),
)
val MonoFontFamily = FontFamily(
    Font(resource = "fonts/geist_mono_regular.ttf", weight = FontWeight.Normal),
    Font(resource = "fonts/geist_mono_medium.ttf", weight = FontWeight.Medium),
)
```

- [ ] **Step 3: Port `Tokens.kt`, `Motion.kt`, `SemanticColors.kt`.** These are pure Compose per the source audit — copy each file, change ONLY the package line (and imports referencing `dev.supermux.android.*` → `dev.supermux.desktop.*`). If any Android-only import sneaks in (e.g. something from `androidx.core`), stop and split just that symbol out — do not carry androidx.core into desktop.

- [ ] **Step 4: Port `SupermuxTheme.kt` minus Android-isms.** Copy, then delete: the `dynamicDarkColorScheme`/`dynamicLightColorScheme` branch (Material You — Android only; desktop is ALWAYS the branded `buildSupermuxScheme` path), the `WindowCompat`/status-bar `SideEffect`, and any `LocalContext`/`Activity` references. Keep: `AppearanceMode` enum, `buildSupermuxScheme`, `LocalSemantics`/`LocalPanes` provision, `SupermuxShapes`, `textScale`. The signature becomes:

```kotlin
@Composable
fun SupermuxTheme(
    appearance: AppearanceMode = AppearanceMode.SYSTEM,
    textScale: Float = 1f,
    content: @Composable () -> Unit,
)
```

For SYSTEM appearance on desktop use `androidx.compose.foundation.isSystemInDarkTheme()` (works on desktop; under bare Xvfb it just resolves dark=false — fine).

- [ ] **Step 5: `Haptics.kt` no-op shim.** The chat/list ports reference `rememberHaptics()`:

```kotlin
package dev.supermux.desktop.theme

import androidx.compose.runtime.Composable

enum class HapticKind { Selection, Confirm, Warning }
class Haptics { fun perform(kind: HapticKind) { /* no haptics on desktop */ } }
@Composable fun rememberHaptics(): Haptics = Haptics()
```

⚠️ Mirror the ACTUAL Android `Haptics.kt` API (read it first) — same names/signatures so ported call sites compile unchanged; the bodies are no-ops.

- [ ] **Step 6: Wire into Main.kt.** Replace `MaterialTheme { … }` with `SupermuxTheme { Surface(color = MaterialTheme.colorScheme.background) { … } }` and set the window title text in `GeistFontFamily`.

- [ ] **Step 7: Build + screenshot.** `:desktop:compileKotlin` green, then the Xvfb run — the screenshot must show the dark branded background (not stock Material purple) and Geist rendering. If fonts fail to load, `Font(resource=…)` paths are classpath-relative — confirm the ttfs landed in `build/resources/main/fonts/`.

- [ ] **Step 8: Commit.**

```bash
git add apps/desktop/src
git commit -m "feat(desktop): theme port — Geist fonts, tokens, semantics, branded SupermuxTheme (no Material You)"
```

---

### Task 4: `DesktopAppState` — broker wiring + frame reducer (TDD)

Port the logic core of `apps/android/src/main/kotlin/dev/supermux/android/AppViewModel.kt` (read it in full first) into a plain class. M1 scope: sessions, messages, activity, agentState, bgTasks, commands, pendingSend, viewing (+heartbeat), ensureMessagesLoaded, sendMessage, interrupt, rename/kill/mute. NOT in M1: uploads, dictation, models/reasoning pickers, finish, git, displays, LSP (later milestones port those blocks).

**Files:**
- Create: `apps/desktop/src/main/kotlin/dev/supermux/desktop/state/DesktopAppState.kt`
- Create: `apps/desktop/src/test/kotlin/dev/supermux/desktop/state/DesktopAppStateReducerTest.kt`

- [ ] **Step 1: Failing reducer test.** The reducer must be a `fun reduce(frame: ServerFrame)` internal method testable without any network. Create the test:

```kotlin
package dev.supermux.desktop.state

import dev.supermux.proto.*
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopAppStateReducerTest {
    private fun state() = DesktopAppState(
        baseUrl = "ws://test:9898", token = "t",
        scope = TestScope(UnconfinedTestDispatcher()), connectOnInit = false,
    )

    @Test fun snapshot_populates_sessions_and_state() {
        val s = state()
        s.reduce(ServerFrame.Snapshot(
            sessions = listOf(SessionInfo(id = "s1", name = "one", workdir = "/w", agent = "claude")),
            logs = mapOf("s1" to listOf(LogEntry(id = "m1", ts = "2026-07-09T00:00:00Z", direction = "out", text = "hi"))),
        ))
        assertEquals(listOf("s1"), s.sessions.value.map { it.id })
        assertEquals("hi", s.messages.value["s1"]?.single()?.text)
    }

    @Test fun message_append_dedups_local_echo() {
        val s = state()
        s.appendLocalEcho("s1", "hello")               // optimistic local- id
        val localId = s.messages.value["s1"]!!.single().id
        assertTrue(localId.startsWith("local-"))
        s.reduce(ServerFrame.MessageAppend(session = "s1",
            entry = LogEntry(id = "real-1", ts = "2026-07-09T00:00:01Z", direction = "in", text = "hello")))
        assertEquals(listOf("real-1"), s.messages.value["s1"]!!.map { it.id })
    }

    @Test fun agent_state_updates_map_and_clears_pending() {
        val s = state()
        s.markPendingSend("s1")
        s.reduce(ServerFrame.AgentState(session = "s1", state = "working", working = true, detail = "thinking"))
        assertTrue(s.agentState.value["s1"]!!.working)
        assertEquals(null, s.pendingSend.value)
    }

    @Test fun session_removed_prunes() {
        val s = state()
        s.reduce(ServerFrame.Snapshot(sessions = listOf(SessionInfo(id = "s1", name = "x", workdir = "/w", agent = "claude"))))
        s.reduce(ServerFrame.SessionRemoved(session = "s1"))
        assertTrue(s.sessions.value.isEmpty())
    }
}
```

⚠️ The `ServerFrame`/`SessionInfo`/`LogEntry` constructor arg lists above are from the exploration report — before running, open `apps/shared/src/commonMain/kotlin/dev/supermux/proto/Frames.kt` and correct any parameter-name drift (e.g. Snapshot's exact fields). The TEST must use the real signatures; adjust the test, not the shared code.

- [ ] **Step 2: Run — FAIL** (`DesktopAppState` unresolved). Same test command as Task 2 with `--tests 'dev.supermux.desktop.state.*'`.

- [ ] **Step 3: Implement `DesktopAppState`.** Structure (port reducer bodies from `AppViewModel.kt` `init{}`'s `when(frame)` — keep the Android logic verbatim wherever it compiles against shared types):

```kotlin
package dev.supermux.desktop.state

import dev.supermux.net.BrokerApi
import dev.supermux.net.BrokerClient
import dev.supermux.proto.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class DesktopAppState(
    val baseUrl: String,
    token: String,
    private val scope: CoroutineScope,
    connectOnInit: Boolean = true,
) {
    private val http = HttpClient(CIO) { install(WebSockets) }
    val client = BrokerClient(baseUrl, token, http)
    val api = BrokerApi(baseUrl, token, http)

    private val _sessions = MutableStateFlow<List<SessionInfo>>(emptyList())
    val sessions: StateFlow<List<SessionInfo>> = _sessions
    private val _messages = MutableStateFlow<Map<String, List<LogEntry>>>(emptyMap())
    val messages: StateFlow<Map<String, List<LogEntry>>> = _messages
    private val _activity = MutableStateFlow<Map<String, List<ActivityEvent>>>(emptyMap())
    val activity: StateFlow<Map<String, List<ActivityEvent>>> = _activity
    private val _agentState = MutableStateFlow<Map<String, AgentStatus>>(emptyMap())
    val agentState: StateFlow<Map<String, AgentStatus>> = _agentState
    private val _bgTasks = MutableStateFlow<Map<String, List<ServerFrame.BgTask>>>(emptyMap())
    val bgTasks: StateFlow<Map<String, List<ServerFrame.BgTask>>> = _bgTasks
    private val _commands = MutableStateFlow<Map<String, List<SlashCommand>>>(emptyMap())
    val commands: StateFlow<Map<String, List<SlashCommand>>> = _commands
    private val _pendingSend = MutableStateFlow<String?>(null)
    val pendingSend: StateFlow<String?> = _pendingSend
    val connected: StateFlow<Boolean> get() = client.sync.synced   // adapt to real ConnectionSyncState API

    init {
        if (connectOnInit) {
            scope.launch { client.frames.collect { reduce(it) } }
            scope.launch { client.run() }
            ensureViewingHeartbeat()
        }
    }

    internal fun reduce(frame: ServerFrame) { /* ported when(frame) reducer */ }

    // ——— ports of AppViewModel members (same names, same logic) ———
    fun appendLocalEcho(session: String, text: String) { /* port */ }
    fun markPendingSend(session: String) { _pendingSend.value = session }
    fun sendMessage(session: String, text: String) { /* appendLocalEcho + markPendingSend + scope.launch { client.send(ClientFrame.Send(...)) } */ }
    fun interrupt(session: String) { scope.launch { runCatching { api.interrupt(session) } } }
    fun rename(session: String, name: String) { scope.launch { runCatching { api.rename(session, name) } } }
    fun kill(session: String) { scope.launch { runCatching { api.kill(session) } } }
    fun setMute(session: String, muted: Boolean) { scope.launch { runCatching { api.setMute(session, muted) } } }
    fun ensureMessagesLoaded(session: String) { /* port: if empty → api.archivedLogs(session), re-check before merge */ }

    // viewing frames (port of updateViewing/sendViewingIfChanged/ensureViewingHeartbeat)
    private var viewingSession: String? = null
    private var viewingVisible = false
    private var lastSentViewing: Pair<String?, Boolean>? = null
    fun updateViewing(session: String?, visible: Boolean) { /* port */ }
    private fun sendViewingIfChanged() { /* port — ClientFrame.Viewing(session, visible) */ }
    private fun ensureViewingHeartbeat() {
        scope.launch { while (isActive) { delay(60_000); if (viewingVisible) client.send(ClientFrame.Viewing(viewingSession, true)) } }
    }

    fun close() { http.close() }
}
```

Port each `/* port */` body from `AppViewModel.kt` lines ~182-350 & 777+ — the source is JVM-compatible Kotlin against shared types; changes are only: `viewModelScope` → `scope`, remove Android imports/notification hooks, remove upload/dictation/model members (later milestones). On Snapshot, reset `lastSentViewing = null` and call `sendViewingIfChanged()` (the reconnect re-assert). Check the real `ConnectionSyncState` API in `BrokerClient.kt` and adapt the `connected` property to it.

- [ ] **Step 4: Tests pass.** Expected: 4 tests green.

- [ ] **Step 5: Commit.**

```bash
git add apps/desktop/src
git commit -m "feat(desktop): DesktopAppState — shared BrokerClient/BrokerApi wiring + ported frame reducer"
```

---

### Task 5: Pairing gate + onboarding UI

Port `pairing/` (drop QR scan; keep Paste + Manual + TOFU dialog) and gate `Main.kt` on the store, with env overrides for dev.

**Files:**
- Create: `apps/desktop/src/main/kotlin/dev/supermux/desktop/pairing/PairingState.kt` (port of `PairingViewModel.kt`, plain class + `StateFlow`)
- Create: `apps/desktop/src/main/kotlin/dev/supermux/desktop/pairing/OnboardingScreen.kt` (port minus Scan mode; `PairTofuDialog` included)
- Modify: `apps/desktop/src/main/kotlin/dev/supermux/desktop/Main.kt`

- [ ] **Step 1: Port `PairingState`.** Same sealed UI states (`Idle/Validating/Confirm/Error/Paired`), same `PairUrl.parse` + `probeDeviceName` via throwaway `BrokerApi(p.baseUrl, p.token, http).pairJson(p.token)` → fallback `me()`. Constructor takes `(store: DesktopTokenStore, scope: CoroutineScope)`. `confirmPersist` writes `store.saveBaseUrl(p.baseUrl); store.save(p.token)`.

- [ ] **Step 2: Port `OnboardingScreen` + `PairTofuDialog`.** Read the Android files; keep the M3 structure (segmented Paste|Manual, text fields, error text, Connect dialog showing `pair.baseUrl` + device name). Desktop delta: no camera/QR; add a hint line "Run `bun run pair <device-name>` on your broker and paste the link".

- [ ] **Step 3: Main.kt pairing gate + env seed.**

```kotlin
fun main() {
    // Dev override, mirrors the mac app's SM_PAIR_TOKEN/SM_PAIR_BASE
    val store = DesktopTokenStore()
    System.getenv("SM_PAIR_TOKEN")?.takeIf { it.isNotBlank() }?.let { tok ->
        store.save(tok)
        System.getenv("SM_PAIR_BASE")?.takeIf { it.isNotBlank() }?.let { store.saveBaseUrl(it) }
    }
    application {
        Window(onCloseRequest = ::exitApplication, title = "supermux",
               state = rememberWindowState(width = 1440.dp, height = 900.dp)) {
            SupermuxTheme {
                var paired by remember {
                    mutableStateOf(!store.load().isNullOrBlank() && !store.loadBaseUrl().isNullOrBlank())
                }
                if (!paired) {
                    val scope = rememberCoroutineScope()
                    val pairing = remember { PairingState(store, scope) }
                    OnboardingScreen(pairing, onPaired = { paired = true })
                } else {
                    val scope = rememberCoroutineScope()
                    val app = remember { DesktopAppState(store.loadBaseUrl()!!, store.load()!!, scope) }
                    DisposableEffect(Unit) { onDispose { app.close() } }
                    WorkspaceRoot(app)   // Task 9 replaces the M1 placeholder body
                }
            }
        }
    }
}
```

For Task 5, `WorkspaceRoot(app)` is a temporary composable that shows `Text("paired to ${app.baseUrl} — ${app.sessions.collectAsState().value.size} sessions")`.

Window focus → viewing visibility: inside `WorkspaceRoot`, observe `LocalWindowInfo.current.isWindowFocused` and call `app.updateViewing(selectedSessionId, focused)` on change (the Android `appVisible` analog).

- [ ] **Step 4: Verify like a user (pairing milestone-check).**
  1. Launch under Xvfb with a FRESH `XDG_CONFIG_HOME` (`XDG_CONFIG_HOME=/home/ahmet/.cache/smx-test-config`), screenshot → onboarding screen visible.
  2. Mint a real token (Ground rules), relaunch with `SM_PAIR_BASE=ws://127.0.0.1:9898 SM_PAIR_TOKEN=<token>`, screenshot → "paired to … — N sessions" with the REAL live session count (nonzero on this box).
  3. Relaunch WITHOUT env vars (same XDG_CONFIG_HOME) → still paired (persistence proven).

- [ ] **Step 5: Commit.**

```bash
git add apps/desktop/src
git commit -m "feat(desktop): pairing gate — onboarding (paste/manual + TOFU), env dev-seed, live-broker verified"
```

---

### Task 6: Session list port

Port `session/SessionListScreen.kt` + `SessionStatusRail.kt` (+ `SessionAvatar` and whatever small siblings it pulls in) → `apps/desktop/.../session/`. Read the Android files first; they consume only shared types + theme + `groupSessions`.

**Files:**
- Create: `apps/desktop/src/main/kotlin/dev/supermux/desktop/session/SessionListPanel.kt` (and sibling files mirroring the Android split)

- [ ] **Step 1: Port.** Mechanical rules for THIS and all later UI ports:
  - package `dev.supermux.android.X` → `dev.supermux.desktop.X`; theme imports → `dev.supermux.desktop.theme`.
  - Agent avatars: Android uses drawables (`R.drawable.*`). Desktop M1 substitutes a `SessionAvatar` that renders the agent's initial letter in a `MonoFontFamily` circle with the agent's brand tint (claude/codex/cursor/opencode) — real logo assets are an M4 polish item. Keep the composable NAME `SessionAvatar` with the same call signature so later swaps are local.
  - `collectAsStateWithLifecycle()` → `collectAsState()` (no lifecycle artifact on desktop).
  - Drop params the desktop M1 shell doesn't have yet (e.g. `sharedScope`/`animScope` shared-element args — delete the parameters AND their usages; no shared-element transitions in M1).
  - Long-press menus (`combinedClickable(onLongClick=…)`) work on desktop but ALSO add right-click: wrap rows in `ContextMenuArea(items = { listOf(ContextMenuItem("Rename"){…}, ContextMenuItem("Mute"){…}, ContextMenuItem("Kill"){…}) })`.
  - `home` param: pass `inferHomeDir(...)` from the first session's workdir exactly like Android's fallback path (grep MainActivity for how `home` is derived; DevConfig.HOME is the Android fallback — desktop uses `System.getProperty("user.home")`).

- [ ] **Step 2: Compile + wire into the Task-5 `WorkspaceRoot` placeholder** (list on the left, empty right side). Xvfb screenshot: real session list, grouped by project, teal working-dots for busy sessions.

- [ ] **Step 3: Commit.** `git add apps/desktop/src && git commit -m "feat(desktop): session list port (groups, status rail, right-click actions)"`

---

### Task 7: Chat stream port (timeline + markdown)

Port `chat/Timeline.kt` (mergeTimeline, StreamRow, TimelineItemRow, ToolCard, UserMessage/AssistantMessage, BreathingDot) and the Android markdown renderer it uses → `apps/desktop/.../chat/`.

**Files:**
- Create: `apps/desktop/src/main/kotlin/dev/supermux/desktop/chat/Timeline.kt` (+ the markdown renderer file(s) it imports — find them by reading Timeline.kt's imports)
- Create: `apps/desktop/src/test/kotlin/dev/supermux/desktop/chat/TimelineMergeTest.kt`

- [ ] **Step 1: Port test first.** Android has timeline tests under `apps/android/src/test/kotlin/.../chat/` — port `mergeTimeline`'s test coverage (find the file with `grep -rl mergeTimeline apps/android/src/test`). If none exists, write:

```kotlin
package dev.supermux.desktop.chat

import dev.supermux.proto.ActivityEvent
import dev.supermux.proto.LogEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class TimelineMergeTest {
    @Test fun interleaves_messages_and_tools_by_ts() {
        val items = mergeTimeline(
            messages = listOf(
                LogEntry(id = "m1", ts = "2026-07-09T10:00:00Z", direction = "out", text = "do it"),
                LogEntry(id = "m2", ts = "2026-07-09T10:02:00Z", direction = "in", text = "done"),
            ),
            activity = listOf(ActivityEvent(/* real ctor — read Frames.kt */)),
        )
        assertEquals(3, items.size)
        assertIs<TimelineItem.Msg>(items.first())
    }
}
```

(Correct `ActivityEvent`'s constructor from Frames.kt before running; FAIL first on unresolved `mergeTimeline`.)

- [ ] **Step 2: Port the code.** Same mechanical rules as Task 6. Specific expectations from the source audit: file-path taps call `onOpenFile: (FilePathRef) -> Unit` — wire it to a no-op `{}` in M1 (editor lands in M3) but KEEP the parameter so M3 is one lambda swap. Text selection: wrap the message column in `SelectionContainer` (Android chat is selectable; desktop must be too). Linkified URLs open via `java.awt.Desktop.getDesktop().browse(uri)` guarded by `Desktop.isDesktopSupported()`.

- [ ] **Step 3: Tests + compile green, commit.** `git commit -m "feat(desktop): chat timeline port (mono gutter, tool cards, markdown)"`

---

### Task 8: Composer + send path

M1 composer = text input + send + interrupt + agent-state line + local (in-memory) draft per session. Attachments/dictation/slash-menu/model-pill are M4-5.

**Files:**
- Create: `apps/desktop/src/main/kotlin/dev/supermux/desktop/chat/DesktopComposer.kt`

- [ ] **Step 1: Build the composer** (new file, desktop-shaped — the Android ChatPanel composer drags in uploads/dictation; don't port it yet):

```kotlin
package dev.supermux.desktop.chat

// imports: material3, foundation, runtime, input.key, dev.supermux.desktop.theme.*

@Composable
fun DesktopComposer(
    draft: String,
    onDraftChange: (String) -> Unit,
    sending: Boolean,
    agentWorking: Boolean,
    onSend: (String) -> Unit,
    onInterrupt: () -> Unit,
) {
    OutlinedTextField(
        value = draft,
        onValueChange = onDraftChange,
        modifier = Modifier.fillMaxWidth().onPreviewKeyEvent { ev ->
            if (ev.type == KeyEventType.KeyDown && ev.key == Key.Enter && !ev.isShiftPressed) {
                val t = draft.trim()
                if (t.isNotEmpty() && !sending) onSend(t)
                true            // consume: Enter sends, Shift+Enter newlines
            } else false
        },
        placeholder = { Text("Message the agent…") },
        trailingIcon = {
            if (agentWorking) IconButton(onClick = onInterrupt) { Icon(Icons.Default.Stop, "Interrupt") }
            else IconButton(enabled = draft.isNotBlank() && !sending,
                onClick = { onSend(draft.trim()) }) { Icon(Icons.AutoMirrored.Filled.Send, "Send") }
        },
        maxLines = 8,
    )
}
```

- [ ] **Step 2: ChatPanel assembly.** Create `apps/desktop/.../chat/ChatPanel.kt`: header (session name + working/thinking status text from `AgentStatus.detail`, "Not responding" banner when `state == "dead"`), `LazyColumn` of `TimelineItemRow`s (auto-scroll to bottom on new items, INSTANT jump on first composition — the "opens at bottom instantly" rule), `DesktopComposer` pinned at the bottom, "Sending…" bubble while `pendingSend == session.id`. Wire `onSend = { app.sendMessage(session.id, it); draft = "" }`, `onInterrupt = { app.interrupt(session.id) }`. Call `app.ensureMessagesLoaded(session.id)` in a `LaunchedEffect(session.id)` and `app.updateViewing(session.id, focused)`.

- [ ] **Step 3: Verify like a user.** Xvfb + live broker: open a real session, screenshot the timeline; SEND a real message to an idle session of this project (e.g. one of the mux worker sessions is fine — send "ping from the desktop app, ignore") and screenshot: local echo → agent goes working → reply appears. This is the core round-trip; do not skip.

- [ ] **Step 4: Commit.** `git commit -m "feat(desktop): chat panel + composer — live send/receive round-trip verified"`

---

### Task 9: Workspace shell — splits, sidebar, shortcuts, menu bar

Port `workspace/` (WorkspaceLayout, ResizableSplit, WorkspaceShortcuts, PaneToggleCluster, SidebarDivider; SessionWorkspaceDetail is the template for the desktop `SessionDetail`) and assemble the real `WorkspaceRoot`.

**Files:**
- Create: `apps/desktop/.../workspace/WorkspaceLayout.kt`, `ResizableSplit.kt`, `WorkspaceShortcuts.kt`, `PaneToggleCluster.kt`, `SessionDetail.kt`, `WorkspaceRoot.kt`
- Create: `apps/desktop/src/test/kotlin/dev/supermux/desktop/workspace/WorkspaceLayoutTest.kt` (port of the Android test)
- Modify: `Main.kt` (menu bar)

- [ ] **Step 1: Port the pure model + its test.** `WorkspaceLayout.kt` and `WorkspaceShortcuts.kt` are pure per the audit — copy (package rename only), and port `apps/android/src/test/kotlin/dev/supermux/android/workspace/WorkspaceLayoutTest.kt` + `WorkspaceShortcutsTest.kt` verbatim. Run: both test classes green.

- [ ] **Step 2: Port `ResizableSplit` + assemble `SessionDetail`.** SessionDetail = ChatPanel + placeholder panes. Placeholder pane composable (used for Editor/Terminal/Display in M1):

```kotlin
@Composable
fun ComingSoonPane(title: String, milestone: String) {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
        contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text("arrives in $milestone", style = MaterialTheme.typography.bodySmall,
                 color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
```

Keep the exact Android split tree (chatFraction → workDisplayFraction → editorTermFraction) and the `PaneToggleCluster` header so M2/M3/M5 only swap pane bodies.

- [ ] **Step 3: WorkspaceRoot + shortcuts + menu bar.** WorkspaceRoot = sidebar (SessionListPanel, collapsible, `sidebarWidth` drag) | SessionDetail for the selected session. Apply `Modifier.workspaceShortcuts(layout, selectedId, onNewSession = { /* M4 */ })`. In `Main.kt` add:

```kotlin
MenuBar {
    Menu("File") {
        Item("New Session", shortcut = KeyShortcut(Key.N, ctrl = true), onClick = { /* M4 launcher */ })
        Separator()
        Item("Unpair…", onClick = { store.clear(); paired = false })
    }
    Menu("View") {
        Item("Toggle Sidebar", shortcut = KeyShortcut(Key.B, ctrl = true), onClick = { layout.sidebarCollapsed = !layout.sidebarCollapsed })
        Item("Toggle Editor", shortcut = KeyShortcut(Key.E, ctrl = true), onClick = { selectedId?.let(layout::toggleEditor) })
        Item("Toggle Terminal", shortcut = KeyShortcut(Key.T, ctrl = true), onClick = { selectedId?.let(layout::toggleTerminal) })
        Item("Toggle Display", shortcut = KeyShortcut(Key.D, ctrl = true), onClick = { selectedId?.let(layout::toggleDisplay) })
    }
}
```

(Hoist `layout`/`selectedId`/`paired` state to `Main` scope as needed — MenuBar lives on the `FrameWindowScope`, outside WorkspaceRoot.) Persist `WorkspaceSnapshot` JSON + `selectedId` to `DesktopTokenStore.defaultPath().parent/ui-state.json` on change (simple `LaunchedEffect(snapshot)` debounce-write; load at startup).

- [ ] **Step 4: Verify like a user + commit.** Xvfb + live broker: screenshot the full workspace (sidebar + chat + toggled placeholder panes); drag a split (xdotool if available, else skip drag and toggle panes via the menu). `git commit -m "feat(desktop): workspace shell — resizable splits, pane toggles, shortcuts, menu bar"`

---

### Task 10: Milestone-1 verification pass (user-mandated) + report

- [ ] **Step 1: Full checklist against the LIVE broker under Xvfb, screenshots for each:**
  1. Fresh config dir → onboarding renders.
  2. Pair via PASTED link in the UI (not env vars): type the `/pair?t=…` URL into the Paste field, TOFU dialog shows broker origin + device name, Connect → workspace appears. (Drive input with `xdotool type`/`key` on :77; if xdotool is unavailable, fall back to env-var pairing and note the UI path as untested-by-hand.)
  3. Session list shows the real fleet, grouped, with live working indicators; select 3 different sessions — chat history loads for each (ensureMessagesLoaded path).
  4. Send "ping from supermux-desktop M1, just reply ok" to an idle session → local echo, working state, reply lands.
  5. Kill/relaunch the app → still paired, same selected session + pane layout (persistence).
  6. `:desktop:test` all green; `:shared:jvmTest` + `:android:compileDebugKotlin` green (nothing broke).
- [ ] **Step 2: Fix anything the checklist catches** (each fix = its own commit).
- [ ] **Step 3: Update the plan file** — tick every completed checkbox in this document, commit as `docs(desktop): M1 plan executed`.
- [ ] **Step 4: Report** to the orchestrating session: what was verified, screenshot paths, deviations from plan, and anything M2 (terminal) should know.

---

## Self-review notes (spec coverage for M1)

Spec items landed here: module/brain/UI architecture (T1/T4/T6-9), pairing incl. paste-token + env override (T5), theme/token layer (T3), persistent credentials (T2), viewing frames + heartbeat (T4/T8), chat parity core: timeline, markdown, selection, links, dead-banner, sending state, instant-bottom (T7/T8), workspace splits/shortcuts/menu (T9), milestone verification mandate (T5/T8/T10). Deferred per spec sequencing: terminal (M2), editor/KCEF + file-path open (M3), launcher/finish/archived/usage/slash/models (M4), display/dictation/notifications (M5), packaging (M6). Known inherited gap (documented in spec): `session_state`/`session_renamed` frames are dropped by shared `Frames.kt` — do NOT hack a desktop-only parser; it's a shared fix out of M1 scope.
