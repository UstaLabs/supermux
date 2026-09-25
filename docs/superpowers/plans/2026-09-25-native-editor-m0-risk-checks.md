# Native editor M0: risk checks. Implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prove or disprove, before any real editor code exists, the four assumptions the native-editor design
(`docs/superpowers/specs/2026-09-25-native-editor-design.md`) rests on:
1. ktreesitter + our own grammar build work on JVM, Android and iOS with Kotlin 2.4.10.
2. web-tree-sitter works inside the wasmJs app and produces **identical** highlight spans.
3. A soft keyboard can drive a hidden text field that holds real document text (autocorrect, Turkish,
   dictation, CJK).
4. The per-platform size of the grammars is acceptable.

**Architecture:** One throwaway Gradle module, `:editor-spike`. It is deleted when M0's results are written
up (Task 9), so nothing in it is production code. A tiny highlighter contract lives in `commonMain`. One
intermediate source set, `treeSitterNative` (jvm + android + ios), implements it with ktreesitter. `wasmJsMain`
implements it with web-tree-sitter. The **same** `commonTest` golden test runs on every backend.

**Tech stack:**
- Kotlin 2.4.10 Multiplatform, `io.github.tree-sitter:ktreesitter:0.25.1`, `web-tree-sitter` 0.25.10 (npm)
- the `tree-sitter-json@0.24.8` npm package (parser.c, queries and a prebuilt .wasm)
- Xcode clang and the Android NDK clang, Compose Multiplatform 1.12.0 (the IME probe only)

## Ground rules for this plan

- **No Gradle on the Linux host.** It is RAM-starved (see `~/.mux/conventions.md`). Every build and test runs
  on the Mac over `ssh mac`, in the private checkout `~/work/native-editor`, never in `~/supermux-desktop-kmp`
  or the shared `~/projects/supermux-dev`.
- Edit files in the worktree on Linux, then sync with the command in Task 0. The Mac's `rsync` is openrsync
  and incompatible with this host's, so use tar over ssh.
- Every `ssh mac` Gradle command starts with the Java prefix. The Mac's non-interactive PATH has no Java:
  define it once in your LOCAL shell (single quotes, so it expands on the Mac, not here):
  ```bash
  MACENV='export JAVA_HOME=/opt/homebrew/opt/openjdk@17; export PATH=$JAVA_HOME/bin:/opt/homebrew/bin:$PATH'
  ```
  and use it as `ssh mac "$MACENV; <command>"`.
- Long jobs: `nohup caffeinate -i <cmd> > ~/work/native-editor/<log> 2>&1 &`, then poll the log. The Mac dozes.
- A spike that fails is a **result**, not a blocker. Write down what failed and why in the results file
  (Task 9) and move on to the next spike. Don't sink hours into forcing it.

---

### Task 0: The Mac private checkout

**Files:**
- Create: `apps/editor-spike/mac-sync.sh`

- [ ] **Step 1: Write the sync script**

```bash
#!/usr/bin/env bash
# Sync this worktree's apps/ + docs/ onto the Mac's PRIVATE checkout ~/work/native-editor.
# tar over ssh (the Mac's rsync is openrsync and cannot talk to GNU rsync). Overlay, never --delete:
# the Mac keeps its own local.properties and build caches.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"
tar czf - \
  --exclude='build' --exclude='.gradle' --exclude='node_modules' --exclude='.kotlin' \
  --exclude='local.properties' --exclude='graphify-out' \
  apps docs | ssh mac 'mkdir -p ~/work/native-editor && tar xzf - -C ~/work/native-editor'
# sdk.dir must point at the MAC's SDK, never the Linux one.
ssh mac 'cd ~/work/native-editor/apps && if [ ! -f local.properties ]; then
  for d in "$HOME/Library/Android/sdk" "$HOME/devtools/android-sdk"; do
    if [ -d "$d" ]; then echo "sdk.dir=$d" > local.properties; break; fi
  done
fi; cat local.properties'
```

- [ ] **Step 2: Run it and check that the Mac configures the build**

Run:
```bash
chmod +x apps/editor-spike/mac-sync.sh && apps/editor-spike/mac-sync.sh
ssh mac "$MACENV; cd ~/work/native-editor/apps && ./gradlew -q help"
```
Expected: `local.properties` prints `sdk.dir=/Users/ahmet/...`, and `help` exits 0.
If configuration fails on `google-services.json`, copy it once:
`ssh mac 'cp ~/projects/supermux-dev/apps/android/google-services.json ~/work/native-editor/apps/android/'`.

- [ ] **Step 3: Commit**

```bash
git add -f apps/editor-spike/mac-sync.sh
git commit -m "chore(editor-spike): mac private-checkout sync script"
```

---

### Task 1: The `:editor-spike` module skeleton

**Files:**
- Modify: `apps/settings.gradle.kts` (after the `:terminal-sample` include)
- Create: `apps/editor-spike/build.gradle.kts`
- Create: `apps/editor-spike/src/commonMain/kotlin/dev/supermux/editor/spike/Highlighter.kt`

- [ ] **Step 1: Include the module**

Append to `apps/settings.gradle.kts`:
```kotlin
// THROWAWAY: the native-editor M0 risk checks (docs/superpowers/plans/2026-09-25-native-editor-m0-risk-checks.md).
// Deleted once the results are written up; nothing may depend on it.
include(":editor-spike")
```

- [ ] **Step 2: Write the build file**

`apps/editor-spike/build.gradle.kts`:
```kotlin
import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSetTree

plugins {
    alias(libs.plugins.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

// Where native/build-grammars.sh stages the grammar libraries (one dir per target).
val grammarsDir = layout.projectDirectory.dir("native/out")

kotlin {
    jvmToolchain(17)
    jvm()
    androidTarget {
        // Run commonTest ON THE DEVICE as instrumented tests: JNI grammars need a real Android runtime.
        instrumentedTestVariant.sourceSetTree.set(KotlinSourceSetTree.test)
    }
    listOf(iosArm64(), iosSimulatorArm64()).forEach { t ->
        t.compilations.getByName("main").cinterops.create("grammars") {
            definitionFile.set(file("src/nativeInterop/cinterop/grammars.def"))
            includeDirs(file("native/include"))
            extraOpts("-libraryPath", grammarsDir.dir(t.konanTarget.name).asFile.absolutePath)
        }
        t.binaries.framework { baseName = "EditorSpike"; isStatic = true }
    }
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs { browser { testTask { useKarma { useChromeHeadless() } } } }
    applyDefaultHierarchyTemplate()

    sourceSets {
        // jvm + android + ios share the ktreesitter implementation.
        val treeSitterNative by creating { dependsOn(commonMain.get()) }
        jvmMain.get().dependsOn(treeSitterNative)
        androidMain.get().dependsOn(treeSitterNative)
        iosMain.get().dependsOn(treeSitterNative)

        commonMain.dependencies {
            implementation(libs.coroutines.core)
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.ui)
            implementation(compose.material3)
        }
        treeSitterNative.dependencies { implementation("io.github.tree-sitter:ktreesitter:0.25.1") }
        commonTest.dependencies { implementation(kotlin("test")) }
        wasmJsMain {
            languageSettings.optIn("kotlin.js.ExperimentalWasmJsInterop")
            dependencies { implementation(npm("web-tree-sitter", "0.25.10")) }
        }
        wasmJsTest { languageSettings.optIn("kotlin.js.ExperimentalWasmJsInterop") }
        val androidInstrumentedTest by getting {
            dependencies {
                implementation("androidx.test:runner:1.6.2")
                implementation("androidx.test.ext:junit:1.2.1")
                implementation(kotlin("test-junit"))
            }
        }
    }
}

android {
    namespace = "dev.supermux.editor.spike"
    compileSdk = libs.versions.androidCompileSdk.get().toInt()
    defaultConfig {
        minSdk = libs.versions.androidMinSdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }
    sourceSets["main"].jniLibs.srcDirs("native/out/jniLibs")
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

tasks.named<Test>("jvmTest") {
    systemProperty(
        "editor.grammars.lib",
        grammarsDir.file("macos_arm64/libeditorgrammars.dylib").asFile.absolutePath,
    )
}
```

- [ ] **Step 3: Write the contract**

`apps/editor-spike/src/commonMain/kotlin/dev/supermux/editor/spike/Highlighter.kt`:
```kotlin
package dev.supermux.editor.spike

/** One highlight capture, in UTF-16 code units: the ONLY unit the real editor will use. */
data class Span(val start: Int, val end: Int, val capture: String) {
    override fun toString() = "$start-$end $capture"
}

/** What M0 needs from a backend: parse, highlight, apply one edit, reparse incrementally. */
interface SpikeHighlighter {
    /** Parse [source] from scratch. */
    fun parse(source: String)
    /** Every capture of [query] over the current tree, sorted by (start, -end, capture). */
    fun highlights(query: String): List<Span>
    /**
     * Replace UTF-16 range [from, to) with [insert] (both the text and the tree), then reparse
     * incrementally. Returns the new source.
     */
    fun edit(from: Int, to: Int, insert: String): String
    fun close()
}

/** Suspend because web-tree-sitter's init and Language.load are async. */
expect suspend fun openJsonHighlighter(): SpikeHighlighter

internal fun List<Span>.sortedForGolden() =
    sortedWith(compareBy<Span>({ it.start }, { -it.end }, { it.capture }))
```

- [ ] **Step 4: Commit**

```bash
git add apps/settings.gradle.kts apps/editor-spike
git commit -m "chore(editor-spike): throwaway M0 module skeleton"
```

---

### Task 2: Build the JSON grammar for every native target

**Files:**
- Create: `apps/editor-spike/native/build-grammars.sh`
- Create: `apps/editor-spike/native/jni_shim.c`
- Create: `apps/editor-spike/native/include/editor_grammars.h`
- Create: `apps/editor-spike/src/nativeInterop/cinterop/grammars.def`
- Create: `apps/editor-spike/.gitignore` containing `native/out/` and `native/src/`

- [ ] **Step 1: The header and the JNI shim**

`native/include/editor_grammars.h`:
```c
#pragma once
typedef struct TSLanguage TSLanguage;
const TSLanguage *tree_sitter_json(void);
```

`native/jni_shim.c`:
```c
// JVM/Android only: hands the grammar pointer to Kotlin as a jlong, which is what
// ktreesitter's JVM `Language(Any)` constructor expects.
#include <jni.h>
#include "editor_grammars.h"

JNIEXPORT jlong JNICALL
Java_dev_supermux_editor_spike_Grammars_json(JNIEnv *env, jclass cls) {
    (void)env; (void)cls;
    return (jlong)(intptr_t)tree_sitter_json();
}
```

`src/nativeInterop/cinterop/grammars.def`:
```
headers = editor_grammars.h
staticLibraries = libeditorgrammars.a
```

- [ ] **Step 2: The build script (runs on the Mac)**

`native/build-grammars.sh`:
```bash
#!/usr/bin/env bash
# Builds libeditorgrammars for: macos_arm64 (JVM tests, dylib + JNI), ios_arm64 + ios_simulator_arm64
# (static, cinterop), android arm64-v8a + x86_64 (shared, JNI). The source comes from the npm package,
# which ships parser.c (+ scanner.c when the grammar has one) and queries/.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
SRC="$HERE/src"; OUT="$HERE/out"; mkdir -p "$SRC" "$OUT"
PKG=tree-sitter-json; VER=0.24.8
if [ ! -f "$SRC/$PKG/src/parser.c" ]; then
  (cd "$SRC" && curl -sL "https://registry.npmjs.org/$PKG/-/$PKG-$VER.tgz" | tar xz && mv package "$PKG")
fi
G="$SRC/$PKG/src"
CFILES=("$G/parser.c"); [ -f "$G/scanner.c" ] && CFILES+=("$G/scanner.c")
CFLAGS=(-Os -fPIC -std=c11 -I"$G" -I"$HERE/include")
JNI_INC=(-I"$JAVA_HOME/include" -I"$JAVA_HOME/include/darwin")

# macOS arm64 dylib for jvmTest
mkdir -p "$OUT/macos_arm64"
clang -arch arm64 "${CFLAGS[@]}" "${JNI_INC[@]}" -dynamiclib "${CFILES[@]}" "$HERE/jni_shim.c" \
  -o "$OUT/macos_arm64/libeditorgrammars.dylib"

# iOS static libs for cinterop
for T in ios_arm64:iphoneos:arm64-apple-ios15.0 ios_simulator_arm64:iphonesimulator:arm64-apple-ios15.0-simulator; do
  IFS=: read -r DIR SDK TRIPLE <<<"$T"; mkdir -p "$OUT/$DIR" "$OUT/$DIR/obj"
  for f in "${CFILES[@]}"; do
    xcrun --sdk "$SDK" clang -target "$TRIPLE" "${CFLAGS[@]}" -c "$f" -o "$OUT/$DIR/obj/$(basename "$f" .c).o"
  done
  rm -f "$OUT/$DIR/libeditorgrammars.a"
  xcrun ar rcs "$OUT/$DIR/libeditorgrammars.a" "$OUT/$DIR"/obj/*.o
done

# Android shared libs (JNI) with the NDK the SDK already has
SDK_DIR="$(sed -n 's/^sdk.dir=//p' "$HERE/../../local.properties")"
NDK="$(ls -d "$SDK_DIR"/ndk/* | sort -V | tail -1)"
TC="$NDK/toolchains/llvm/prebuilt/darwin-x86_64/bin"
for T in arm64-v8a:aarch64-linux-android26 x86_64:x86_64-linux-android26; do
  IFS=: read -r ABI TRIPLE <<<"$T"; mkdir -p "$OUT/jniLibs/$ABI"
  "$TC/$TRIPLE-clang" "${CFLAGS[@]}" -shared "${CFILES[@]}" "$HERE/jni_shim.c" \
    -o "$OUT/jniLibs/$ABI/libeditorgrammars.so"
done
ls -l "$OUT"/*/libeditorgrammars.* "$OUT"/jniLibs/*/libeditorgrammars.so
```

- [ ] **Step 3: Run it on the Mac**

Run:
```bash
apps/editor-spike/mac-sync.sh
ssh mac "$MACENV; bash ~/work/native-editor/apps/editor-spike/native/build-grammars.sh"
```
Expected: five libraries listed (one dylib, two `.a`, two `.so`). If no NDK is installed, record that in
the results file, skip the Android part and carry on. Android then gets checked in the M5 device pass.

- [ ] **Step 4: Commit**

```bash
git add -f apps/editor-spike/native apps/editor-spike/src/nativeInterop apps/editor-spike/.gitignore
git commit -m "chore(editor-spike): build the JSON grammar for JVM, iOS and Android"
```

---

### Task 3: The golden test (shared by every backend)

**Files:**
- Create: `apps/editor-spike/src/commonTest/kotlin/dev/supermux/editor/spike/GoldenHighlightTest.kt`

The sample contains `ğ` (one UTF-16 unit, two UTF-8 bytes) and `😀` (a surrogate pair: two UTF-16 units,
four UTF-8 bytes). A backend that mixes up byte and char offsets gets every span after those characters
wrong, which is exactly the bug this test exists to catch. Offsets below are UTF-16 indexes into `SAMPLE`,
worked out by hand.

- [ ] **Step 1: Write the test**

```kotlin
package dev.supermux.editor.spike

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

// tree-sitter-json 0.24.8 queries/highlights.scm, verbatim.
const val JSON_HIGHLIGHTS = """
(pair
  key: (_) @string.special.key)

(string) @string

(number) @number

[
  (null)
  (true)
  (false)
] @constant.builtin

(escape_sequence) @escape

(comment) @comment
"""

// Raw string: the \n below is a backslash + n in the JSON (an escape_sequence), not a newline.
const val SAMPLE = """{"ağ": [1, true, null], "e😀": "x\n"}"""

class GoldenHighlightTest {
    @Test
    fun highlightsAreUtf16AndIdenticalOnEveryBackend() = runTest {
        val h = openJsonHighlighter()
        try {
            h.parse(SAMPLE)
            assertEquals(
                listOf(
                    "1-5 string", "1-5 string.special.key",
                    "8-9 number",
                    "11-15 constant.builtin",
                    "17-21 constant.builtin",
                    "24-29 string", "24-29 string.special.key",
                    "31-36 string",
                    "33-35 escape",
                ),
                h.highlights(JSON_HIGHLIGHTS).map { it.toString() },
            )
        } finally { h.close() }
    }

    @Test
    fun incrementalEditShiftsLaterSpans() = runTest {
        val h = openJsonHighlighter()
        try {
            h.parse(SAMPLE)
            // Replace `1` (8..9) with `12345`: +4 units. Everything after index 9 moves by 4.
            val next = h.edit(8, 9, "12345")
            assertEquals(SAMPLE.replaceRange(8, 9, "12345"), next)
            val spans = h.highlights(JSON_HIGHLIGHTS).map { it.toString() }
            assertEquals("8-13 number", spans[2])
            assertEquals("35-40 string", spans[7])
            assertEquals("37-39 escape", spans[8])
        } finally { h.close() }
    }
}
```
Add `implementation(libs.coroutines.test)` to `commonTest.dependencies` in `apps/editor-spike/build.gradle.kts`.
⚠️ `~/.mux/domains/infra.digest.md` notes that coroutines-test 1.9.0 **does not link on wasmJs** with Kotlin
2.4.10. If the wasm link fails with `Key kotlin.text/substring… is missing`, replace `runTest` in this file with
a tiny `expect fun runSpike(block: suspend () -> Unit)`: `runBlocking` on jvm/android/ios, and on wasmJs
return a `Promise` from `GlobalScope.promise { block() }`, which Kotlin/Wasm's Karma integration awaits.

- [ ] **Step 2: Commit**

```bash
git add apps/editor-spike
git commit -m "test(editor-spike): shared UTF-16 golden highlight test"
```

---

### Task 4: Spike 1a: ktreesitter on the JVM

**Files:**
- Create: `apps/editor-spike/src/treeSitterNative/kotlin/dev/supermux/editor/spike/KtsHighlighter.kt`
- Create: `apps/editor-spike/src/jvmMain/kotlin/dev/supermux/editor/spike/Grammars.jvm.kt`
- Create: `apps/editor-spike/src/treeSitterNative/kotlin/dev/supermux/editor/spike/Grammars.kt`

- [ ] **Step 1: The shared ktreesitter implementation**

`src/treeSitterNative/kotlin/dev/supermux/editor/spike/Grammars.kt`:
```kotlin
package dev.supermux.editor.spike

/** The raw TSLanguage pointer in whatever form ktreesitter's `Language(Any)` wants on this platform. */
internal expect fun jsonLanguagePointer(): Any
```

`src/treeSitterNative/kotlin/dev/supermux/editor/spike/KtsHighlighter.kt`:
```kotlin
package dev.supermux.editor.spike

import io.github.treesitter.ktreesitter.InputEdit
import io.github.treesitter.ktreesitter.InputEncoding
import io.github.treesitter.ktreesitter.Language
import io.github.treesitter.ktreesitter.Parser
import io.github.treesitter.ktreesitter.Point
import io.github.treesitter.ktreesitter.Query
import io.github.treesitter.ktreesitter.Tree

/**
 * UTF-16LE on purpose: tree-sitter then reports BYTE offsets that are exactly 2 × the UTF-16 index,
 * so the editor never needs UTF-8 counts. Proving that is half of this spike.
 */
class KtsHighlighter : SpikeHighlighter {
    private val language = Language(jsonLanguagePointer())
    private val parser = Parser(language)
    private var source = ""
    private var tree: Tree? = null

    private fun reparse(old: Tree?) {
        val text = source
        tree = parser.parse(InputEncoding.UTF_16LE, old) { byte, _ ->
            val i = (byte / 2u).toInt()
            if (i >= text.length) "" else text.subSequence(i, minOf(text.length, i + 1024))
        }
    }

    override fun parse(source: String) { this.source = source; reparse(null) }

    override fun highlights(query: String): List<Span> {
        val q = Query(language, query)
        val root = tree!!.rootNode
        return q(root).captures().map { (i, match) ->
            val c = match.captures[i.toInt()]
            Span((c.node.startByte / 2u).toInt(), (c.node.endByte / 2u).toInt(), c.name)
        }.toList().sortedForGolden()
    }

    override fun edit(from: Int, to: Int, insert: String): String {
        val old = source
        source = old.replaceRange(from, to, insert)
        val t = tree!!
        t.edit(
            InputEdit(
                startByte = (from * 2).toUInt(),
                oldEndByte = (to * 2).toUInt(),
                newEndByte = ((from + insert.length) * 2).toUInt(),
                startPoint = pointAt(old, from),
                oldEndPoint = pointAt(old, to),
                newEndPoint = pointAt(source, from + insert.length),
            ),
        )
        reparse(t)
        return source
    }

    override fun close() { tree = null }

    /** tree-sitter's Point column is in BYTES of the chosen encoding: 2 × UTF-16 units here. */
    private fun pointAt(text: String, index: Int): Point {
        val lineStart = text.lastIndexOf('\n', index - 1) + 1
        val row = text.subSequence(0, index).count { it == '\n' }
        return Point(row.toUInt(), ((index - lineStart) * 2).toUInt())
    }
}

actual suspend fun openJsonHighlighter(): SpikeHighlighter = KtsHighlighter()
```

- [ ] **Step 2: The JVM pointer**

`src/jvmMain/kotlin/dev/supermux/editor/spike/Grammars.jvm.kt`:
```kotlin
package dev.supermux.editor.spike

internal object Grammars {
    init { System.load(System.getProperty("editor.grammars.lib") ?: error("editor.grammars.lib not set")) }
    @JvmStatic external fun json(): Long
}

internal actual fun jsonLanguagePointer(): Any = Grammars.json()
```

- [ ] **Step 3: Run on the Mac**

Run:
```bash
apps/editor-spike/mac-sync.sh
ssh mac "$MACENV; cd ~/work/native-editor/apps && ./gradlew :editor-spike:jvmTest --console=plain"
```
Expected: `GoldenHighlightTest` passes both tests.
If the capture sequence API differs from what is written here (`captures()` returns
`Sequence<Pair<UInt, QueryMatch>>` in 0.25.1), adjust *only* `highlights()`. The test is the contract.

- [ ] **Step 4: Commit**

```bash
git add apps/editor-spike
git commit -m "spike(editor): ktreesitter + own JSON grammar pass the golden test on the JVM"
```

---

### Task 5: Spike 1b: ktreesitter on iOS (simulator)

**Files:**
- Create: `apps/editor-spike/src/iosMain/kotlin/dev/supermux/editor/spike/Grammars.ios.kt`

- [ ] **Step 1: The iOS pointer**

```kotlin
package dev.supermux.editor.spike

import dev.supermux.editor.spike.grammars.tree_sitter_json
import kotlinx.cinterop.ExperimentalForeignApi

@OptIn(ExperimentalForeignApi::class)
internal actual fun jsonLanguagePointer(): Any = tree_sitter_json()!!
```
(The cinterop package defaults to `grammars`. If the generated package name differs, take it from
`build/classes/kotlin/iosSimulatorArm64/main/cinterop/`.)

- [ ] **Step 2: Run on the Mac (it boots a simulator itself)**

Run:
```bash
apps/editor-spike/mac-sync.sh
ssh mac "$MACENV; cd ~/work/native-editor/apps && ./gradlew :editor-spike:iosSimulatorArm64Test --console=plain"
```
Expected: both golden tests pass.
**This is the Kotlin 2.2.21-built klib vs our 2.4.10 compiler check.** If it fails at klib resolution or ABI,
record the exact error. The fallback to evaluate in the results is building ktreesitter from source with
our Kotlin version (clone at tag 0.25.1, bump `kotlin-stdlib` in its `gradle/libs.versions.toml`, then
`publishToMavenLocal`).

- [ ] **Step 3: Also link a device framework (catches link-time-only problems)**

Run: `ssh mac "$MACENV; cd ~/work/native-editor/apps && ./gradlew :editor-spike:linkDebugFrameworkIosArm64 --console=plain"`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add apps/editor-spike
git commit -m "spike(editor): ktreesitter golden test on the iOS simulator"
```

---

### Task 6: Spike 1c: ktreesitter on Android (the Galaxy Fold)

**Files:**
- Create: `apps/editor-spike/src/androidMain/kotlin/dev/supermux/editor/spike/Grammars.android.kt`

- [ ] **Step 1: The Android pointer**

```kotlin
package dev.supermux.editor.spike

internal object Grammars {
    init { System.loadLibrary("editorgrammars") }
    @JvmStatic external fun json(): Long
}

internal actual fun jsonLanguagePointer(): Any = Grammars.json()
```

- [ ] **Step 2: Build the test APK on the Mac and bring it back**

Run:
```bash
apps/editor-spike/mac-sync.sh
ssh mac "$MACENV; cd ~/work/native-editor/apps && ./gradlew :editor-spike:assembleDebugAndroidTest --console=plain"
scp mac:work/native-editor/apps/editor-spike/build/outputs/apk/androidTest/debug/editor-spike-debug-androidTest.apk /tmp/claude-1000/
```
Expected: the APK is copied. It contains `lib/arm64-v8a/libeditorgrammars.so` and ktreesitter's
`libktreesitter.so` (check with `unzip -l`).

- [ ] **Step 3: Run it on the Fold**

The Fold is reached over wireless adb. If the connection is stale, follow the `mux:wireless-adb-over-vpn`
skill. This is a separate test package, so it cannot touch the Supermux app's data.
```bash
adb install -r -t /tmp/claude-1000/editor-spike-debug-androidTest.apk
adb shell am instrument -w dev.supermux.editor.spike.test/androidx.test.runner.AndroidJUnitRunner
adb uninstall dev.supermux.editor.spike.test
```
Expected: `OK (2 tests)`. ⛔ Never uninstall `dev.supermux.*` app packages. Only the `.editor.spike.test`
package this task installed.

- [ ] **Step 4: Commit**

```bash
git add apps/editor-spike
git commit -m "spike(editor): ktreesitter golden test on Android"
```

---

### Task 7: Spike 2: web-tree-sitter in wasmJs

**Files:**
- Create: `apps/editor-spike/src/wasmJsMain/kotlin/dev/supermux/editor/spike/WebHighlighter.kt`
- Create: `apps/editor-spike/karma.config.d/tree-sitter.js`
- Create: `apps/editor-spike/src/wasmJsTest/resources/` (filled by Step 1)

- [ ] **Step 1: Stage the two wasm files as test resources**

Append to `native/build-grammars.sh`:
```bash
# web: the runtime wasm from web-tree-sitter and the grammar wasm the npm package ships prebuilt
RES="$HERE/../src/wasmJsTest/resources"; mkdir -p "$RES"
cp "$SRC/$PKG/tree-sitter-json.wasm" "$RES/"
curl -sL "https://registry.npmjs.org/web-tree-sitter/-/web-tree-sitter-0.25.10.tgz" | tar xz -C "$SRC"
cp "$SRC/package/tree-sitter.wasm" "$RES/" 2>/dev/null || cp "$SRC/package/web-tree-sitter.wasm" "$RES/tree-sitter.wasm"
```
Add `src/wasmJsTest/resources/*.wasm` to `apps/editor-spike/.gitignore`.

- [ ] **Step 2: Serve them to the Karma page**

`karma.config.d/tree-sitter.js`:
```javascript
// Serve the runtime + grammar wasm next to the test bundle at /ts/<file>.
;(function () {
  var path = require("path")
  config.files = config.files || []
  ;["tree-sitter.wasm", "tree-sitter-json.wasm"].forEach(function (f) {
    config.files.push({ pattern: path.resolve(config.basePath, "kotlin/" + f), included: false, served: true, watched: false })
  })
  config.proxies = Object.assign({}, config.proxies, { "/ts/": "/base/kotlin/" })
  config.mime = Object.assign({}, config.mime, { "application/wasm": ["wasm"] })
  config.client = config.client || {}
  config.client.mocha = Object.assign({}, config.client.mocha, { timeout: 60000 })
})();
```

- [ ] **Step 3: The web-tree-sitter backend**

`src/wasmJsMain/kotlin/dev/supermux/editor/spike/WebHighlighter.kt`:
```kotlin
package dev.supermux.editor.spike

import kotlinx.coroutines.await
import kotlin.js.Promise

// Thin JS glue: web-tree-sitter's API is object-heavy, so do the work in JS and hand Kotlin plain
// strings/ints. The real editor-syntax module will keep this shape (data out, no JS objects leak).
@JsFun("""async () => {
  const TS = await import('web-tree-sitter');
  await TS.Parser.init({ locateFile: (f) => '/ts/tree-sitter.wasm' });
  const lang = await TS.Language.load('/ts/tree-sitter-json.wasm');
  const parser = new TS.Parser(); parser.setLanguage(lang);
  return { TS, lang, parser, tree: null, src: '' };
}""")
private external fun jsOpen(): Promise<JsAny>

@JsFun("(h, s) => { h.src = s; h.tree = h.parser.parse(s); }")
private external fun jsParse(h: JsAny, s: String)

// Flattened "start end capture" lines; JS string indexes ARE UTF-16 units.
@JsFun("""(h, q) => {
  const query = new h.TS.Query(h.lang, q);
  return query.captures(h.tree.rootNode).map(c => c.node.startIndex + ' ' + c.node.endIndex + ' ' + c.name).join('\n');
}""")
private external fun jsHighlights(h: JsAny, q: String): String

@JsFun("""(h, from, to, ins) => {
  const pos = (s, i) => { const b = s.slice(0, i); const r = b.split('\n').length - 1; return { row: r, column: i - (b.lastIndexOf('\n') + 1) }; };
  const old = h.src; const next = old.slice(0, from) + ins + old.slice(to);
  h.tree.edit({ startIndex: from, oldEndIndex: to, newEndIndex: from + ins.length,
    startPosition: pos(old, from), oldEndPosition: pos(old, to), newEndPosition: pos(next, from + ins.length) });
  h.src = next; h.tree = h.parser.parse(next, h.tree); return next;
}""")
private external fun jsEdit(h: JsAny, from: Int, to: Int, ins: String): String

class WebHighlighter(private val h: JsAny) : SpikeHighlighter {
    override fun parse(source: String) = jsParse(h, source)
    override fun highlights(query: String): List<Span> =
        jsHighlights(h, query).lineSequence().filter { it.isNotBlank() }.map {
            val (s, e, c) = it.split(' ', limit = 3); Span(s.toInt(), e.toInt(), c)
        }.toList().sortedForGolden()
    override fun edit(from: Int, to: Int, insert: String) = jsEdit(h, from, to, insert)
    override fun close() {}
}

actual suspend fun openJsonHighlighter(): SpikeHighlighter = WebHighlighter(jsOpen().await())
```

- [ ] **Step 4: Run on the Mac (headless Chrome)**

Run:
```bash
apps/editor-spike/mac-sync.sh
ssh mac "$MACENV; bash ~/work/native-editor/apps/editor-spike/native/build-grammars.sh >/dev/null && cd ~/work/native-editor/apps && CHROME_BIN='/Applications/Google Chrome.app/Contents/MacOS/Google Chrome' ./gradlew :editor-spike:wasmJsBrowserTest --console=plain"
```
Expected: both golden tests pass, with **the same expected lists as the JVM and iOS runs**. That is the
"identical colours on every client" claim from the spec.
If the dynamic `import('web-tree-sitter')` doesn't resolve under karma-webpack, switch to a `@JsModule("web-tree-sitter")`
external declaration of `Parser`, `Language` and `Query`. Record which one worked; editor-syntax will copy it.

- [ ] **Step 5: Commit**

```bash
git add apps/editor-spike
git commit -m "spike(editor): web-tree-sitter produces the same golden spans in wasmJs"
```

---

### Task 8: Spike 3: the soft-keyboard hidden field (iOS device)

The terminal's hidden field (`apps/terminal-compose/.../TerminalTextInput.kt`) only holds a zero-width seed.
The editor's must hold **real text around the cursor**, so the keyboard sees context for autocorrect,
suggestions and dictation. This probe checks that turning every field change into an edit reproduces the
document exactly.

**Files:**
- Create: `apps/editor-spike/src/commonMain/kotlin/dev/supermux/editor/spike/ImeProbe.kt`
- Create: `apps/editor-spike/src/iosMain/kotlin/dev/supermux/editor/spike/ImeProbeViewController.kt`
- Create: `apps/editor-spike/iosProbe/project.yml`
- Create: `apps/editor-spike/iosProbe/Sources/App.swift`
- Test: `apps/editor-spike/src/commonTest/kotlin/dev/supermux/editor/spike/WindowDiffTest.kt`

- [ ] **Step 1: Write the failing test for the pure window/diff logic**

```kotlin
package dev.supermux.editor.spike

import kotlin.test.Test
import kotlin.test.assertEquals

class WindowDiffTest {
    @Test fun insertInMiddle() = assertEquals(Edit(2, 2, "X"), diffField("abcd", "abXcd"))
    @Test fun autocorrectReplacesWord() = assertEquals(Edit(4, 7, "the"), diffField("hi, teh.", "hi, the."))
    @Test fun deleteAtStart() = assertEquals(Edit(0, 1, ""), diffField("ab", "b"))
    @Test fun identical() = assertEquals(null, diffField("ab", "ab"))
    @Test fun repeatedCharsPreferTheCursorSide() =
        // "aa" -> "aaa" with the cursor at 2 is an insert AT 2, not at 0.
        assertEquals(Edit(2, 2, "a"), diffField("aa", "aaa", cursorAfter = 3))

    @Test fun windowMapsBackToDocument() {
        val doc = "0123456789abcdef"
        val w = FieldWindow.around(doc, cursor = 10, radius = 3)   // "789abc", base 7
        assertEquals("789abc", w.text)
        val edit = diffField(w.text, "789Zabc")!!                   // user typed Z at doc 10
        assertEquals("0123456789Zabcdef", w.applyTo(doc, edit))
    }
}
```

- [ ] **Step 2: Run it and check that it fails**

Run: `ssh mac "$MACENV; cd ~/work/native-editor/apps && ./gradlew :editor-spike:jvmTest --tests '*WindowDiffTest*' --console=plain"`
(after `mac-sync.sh`). Expected: compilation FAILS (`diffField`, `Edit` and `FieldWindow` are not defined).

- [ ] **Step 3: Implement the logic plus the probe composable**

`src/commonMain/kotlin/dev/supermux/editor/spike/ImeProbe.kt`:
```kotlin
package dev.supermux.editor.spike

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp

/** A replacement of document range [from, to) by [insert]. */
data class Edit(val from: Int, val to: Int, val insert: String)

/**
 * The smallest single replacement turning [before] into [after]. When repeated characters make the
 * split ambiguous, the common prefix is capped at the cursor, so the edit lands where the user typed.
 */
fun diffField(before: String, after: String, cursorAfter: Int = after.length): Edit? {
    if (before == after) return null
    val maxPrefix = minOf(before.length, after.length)
    var p = 0
    while (p < maxPrefix && before[p] == after[p]) p++
    val growth = after.length - before.length
    if (growth > 0) p = minOf(p, maxOf(0, cursorAfter - growth))
    var s = 0
    while (s < before.length - p && s < after.length - p &&
        before[before.length - 1 - s] == after[after.length - 1 - s]) s++
    return Edit(p, before.length - s, after.substring(p, after.length - s))
}

/** The slice of the document the hidden field shows: [text] starts at document offset [base]. */
data class FieldWindow(val base: Int, val text: String) {
    fun applyTo(doc: String, e: Edit): String = doc.replaceRange(base + e.from, base + e.to, e.insert)
    companion object {
        fun around(doc: String, cursor: Int, radius: Int): FieldWindow {
            val start = maxOf(0, cursor - radius); val end = minOf(doc.length, cursor + radius)
            return FieldWindow(start, doc.substring(start, end))
        }
    }
}

/**
 * The on-device probe. The top line is the DOCUMENT, rebuilt only from diffed edits. The field below it
 * is what the keyboard edits (visible here on purpose; the real editor hides it). If the document
 * and the field ever disagree, the approach is broken, and the log shows the exact change that did it.
 */
@Composable
fun ImeProbe(radius: Int = 24) {
    var doc by remember { mutableStateOf("Merhaba dünya. teh quick brown fox\nsecond line") }
    var window by remember { mutableStateOf(FieldWindow.around(doc, doc.length, radius)) }
    val field = remember { TextFieldState(window.text, TextRange(window.text.length)) }
    val log = remember { mutableStateListOf<String>() }

    LaunchedEffect(field) {
        var before = field.text.toString()
        snapshotFlow { field.text.toString() to field.selection }.collect { (after, sel) ->
            val e = diffField(before, after, sel.end)
            if (e != null) {
                doc = window.applyTo(doc, e)
                val cursorInDoc = window.base + sel.end
                window = FieldWindow.around(doc, cursorInDoc, radius)
                log.add(0, "$e comp=${field.composition} → field='${after.replace("\n", "⏎")}'")
                // Re-window once the cursor nears an edge, the way the real editor will.
                if (window.text != after) {
                    field.edit { replace(0, length, window.text); selection = TextRange(cursorInDoc - window.base) }
                }
            }
            before = field.text.toString()
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("DOCUMENT: " + doc.replace("\n", "⏎"))
        Text("FIELD (base ${window.base}): ")
        BasicTextField(field, Modifier.padding(vertical = 8.dp))
        LazyColumn { items(log) { Text(it) } }
    }
}
```
`src/iosMain/kotlin/dev/supermux/editor/spike/ImeProbeViewController.kt`:
```kotlin
package dev.supermux.editor.spike

import androidx.compose.ui.window.ComposeUIViewController

fun imeProbeViewController() = ComposeUIViewController { ImeProbe() }
```

- [ ] **Step 4: Run the unit test and check that it passes**

Run the Step 2 command. Expected: 6 tests PASS.

- [ ] **Step 5: A minimal Xcode host app**

`iosProbe/project.yml`:
```yaml
name: EditorImeProbe
options: { bundleIdPrefix: dev.supermux.spike, deploymentTarget: { iOS: "17.0" } }
targets:
  EditorImeProbe:
    type: application
    platform: iOS
    sources: [Sources]
    settings:
      base:
        FRAMEWORK_SEARCH_PATHS: $(SRCROOT)/../build/bin/iosArm64/debugFramework
        OTHER_LDFLAGS: [-framework, EditorSpike]
        GENERATE_INFOPLIST_FILE: YES
        INFOPLIST_KEY_UILaunchScreen_Generation: YES
```
`iosProbe/Sources/App.swift`:
```swift
import SwiftUI
import EditorSpike

struct Probe: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController { ImeProbeViewControllerKt.imeProbeViewController() }
    func updateUIViewController(_ vc: UIViewController, context: Context) {}
}

@main struct EditorImeProbeApp: App {
    var body: some Scene { WindowGroup { Probe().ignoresSafeArea(.keyboard) } }
}
```

- [ ] **Step 6: Build and install on the iPhone 15 Pro**

Follow the `mux:ios-device-on-remote-mac` skill for signing (the dedicated keychain unlock and the headless
`xcodebuild` flags). `mac:~/work/terminal-core-6362d85c/tc-device.sh` is the working template: copy it into
`~/work/native-editor/` and point it at `apps/editor-spike/iosProbe` instead of `apps/iosApp`.
```bash
ssh mac "$MACENV; cd ~/work/native-editor/apps && ./gradlew :editor-spike:linkDebugFrameworkIosArm64 && cd editor-spike/iosProbe && xcodegen generate"
# then the tc-device.sh-style xcodebuild + `xcrun devicectl device install app --device 7CFA6CA8-E662-5C77-8AFE-BE12DCF52A16 <app>`
```
Expected: EditorImeProbe launches on the phone.

- [ ] **Step 7: The manual pass. Ahmet does this on the phone and reports back.**

Send Ahmet this checklist through the reply tool. Each item passes if the DOCUMENT line is correct afterwards:
1. Type `teh ` with autocorrect on: the document shows `the `.
2. Switch to the Turkish keyboard and type `ğüşıöç`.
3. Long-press Backspace until the field's left edge has been passed (tests re-windowing).
4. Backspace with the cursor at the very start of the document: nothing breaks.
5. Return inserts `⏎`.
6. Dictate a sentence (the system mic, and openwhisper if it is installed).
7. Japanese kana → kanji conversion: the document changes only on commit, and the log shows `comp=`.
8. Tap a predictive-bar suggestion.
Record every failure with its log line.

- [ ] **Step 8: Commit**

```bash
git add apps/editor-spike
git commit -m "spike(editor): iOS soft-keyboard probe for the real-text hidden field"
```

---

### Task 9: Spike 4: grammar inventory and size, then write the results

**Files:**
- Create: `apps/editor-spike/native/inventory.sh`
- Create: `docs/superpowers/notes/2026-09-native-editor-m0-results.md`

- [ ] **Step 1: The inventory script (runs on the Mac)**

```bash
#!/usr/bin/env bash
# For every language today's cm6 bundle highlights: which npm grammar package exists, its version and
# licence, whether it ships parser.c / scanner.c / queries/highlights.scm / a prebuilt .wasm, and the
# compiled size (-Os, ios arm64 object) + wasm size. Output: TSV on stdout.
set -uo pipefail
TMP="$(mktemp -d)"; cd "$TMP"
LANGS="javascript:tree-sitter-javascript typescript:tree-sitter-typescript python:tree-sitter-python
rust:tree-sitter-rust go:tree-sitter-go java:tree-sitter-java c:tree-sitter-c cpp:tree-sitter-cpp
php:tree-sitter-php html:tree-sitter-html css:tree-sitter-css json:tree-sitter-json
yaml:@tree-sitter-grammars/tree-sitter-yaml markdown:@tree-sitter-grammars/tree-sitter-markdown
sql:@derekstride/tree-sitter-sql xml:@tree-sitter-grammars/tree-sitter-xml vue:tree-sitter-vue
wast:tree-sitter-wast shell:tree-sitter-bash kotlin:@tree-sitter-grammars/tree-sitter-kotlin
dart:tree-sitter-dart csharp:tree-sitter-c-sharp scala:tree-sitter-scala objc:tree-sitter-objc
ruby:tree-sitter-ruby swift:tree-sitter-swift groovy:tree-sitter-groovy lua:@tree-sitter-grammars/tree-sitter-lua
perl:tree-sitter-perl r:tree-sitter-r julia:tree-sitter-julia haskell:tree-sitter-haskell
erlang:tree-sitter-erlang ocaml:tree-sitter-ocaml fsharp:tree-sitter-fsharp clojure:tree-sitter-clojure
elm:@elm-tooling/tree-sitter-elm crystal:tree-sitter-crystal coffeescript:tree-sitter-coffeescript
fortran:tree-sitter-fortran pascal:tree-sitter-pascal vb:tree-sitter-vb haxe:tree-sitter-haxe
cmake:tree-sitter-cmake dockerfile:tree-sitter-dockerfile nginx:tree-sitter-nginx glsl:tree-sitter-glsl"
printf "lang\tpackage\tversion\tlicense\tparser\tscanner\thighlights\twasm\tios_obj_bytes\twasm_bytes\n"
for pair in $LANGS; do
  L="${pair%%:*}"; P="${pair#*:}"
  META="$(npm view "$P" version license --json 2>/dev/null)" || { printf "%s\t%s\tMISSING\n" "$L" "$P"; continue; }
  V="$(echo "$META" | python3 -c 'import json,sys;print(json.load(sys.stdin)["version"])')"
  LIC="$(echo "$META" | python3 -c 'import json,sys;print(json.load(sys.stdin).get("license"))')"
  mkdir -p "$L" && (cd "$L" && npm pack "$P@$V" --silent >/dev/null 2>&1 && tar xzf ./*.tgz)
  D="$L/package"; S="$(find "$D" -path '*/src/parser.c' | head -1)"; SD="$(dirname "${S:-x}")"
  HAS_SC=$([ -f "$SD/scanner.c" ] && echo y || echo n)
  HAS_HL=$(find "$D" -name highlights.scm | grep -q . && echo y || echo n)
  W="$(find "$D" -maxdepth 2 -name '*.wasm' | head -1)"
  OBJ=0
  if [ -n "$S" ]; then
    xcrun --sdk iphoneos clang -target arm64-apple-ios15.0 -Os -I"$SD" -c "$S" -o p.o 2>/dev/null && OBJ=$(stat -f%z p.o)
    [ "$HAS_SC" = y ] && xcrun --sdk iphoneos clang -target arm64-apple-ios15.0 -Os -I"$SD" -c "$SD/scanner.c" -o s.o 2>/dev/null && OBJ=$((OBJ + $(stat -f%z s.o)))
  fi
  printf "%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n" "$L" "$P" "$V" "$LIC" "$([ -n "$S" ] && echo y || echo n)" "$HAS_SC" "$HAS_HL" "$([ -n "$W" ] && echo y || echo n)" "$OBJ" "$([ -n "$W" ] && stat -f%z "$W" || echo 0)"
done
rm -rf "$TMP"
```

- [ ] **Step 2: Run it**

Run:
```bash
apps/editor-spike/mac-sync.sh
ssh mac 'export PATH=/opt/homebrew/bin:$PATH; bash ~/work/native-editor/apps/editor-spike/native/inventory.sh' > /tmp/claude-1000/grammar-inventory.tsv
column -t -s $'\t' /tmp/claude-1000/grammar-inventory.tsv
```
Expected: one row per language. `MISSING` or `parser=n` rows are the plain-text candidates. Where a
package name is wrong, try the obvious alternative (`npm search tree-sitter-<lang>`) and note it.

- [ ] **Step 3: Write the results**

`docs/superpowers/notes/2026-09-native-editor-m0-results.md`, with these sections:
- **1. ktreesitter** (JVM / iOS sim / iOS device link / Android device): pass or fail, and the exact error
  on a failure.
- **2. UTF-16:** did the UTF-16LE parse give byte offsets = 2 × UTF-16 index on every native backend? If
  yes, the spec's "rope stores UTF-8 byte counts" is **dropped**: the editor uses UTF-16 everywhere.
- **3. web-tree-sitter:** pass or fail, and which loading style worked (dynamic import vs `@JsModule`).
- **4. IME probe:** the 8 checklist results from Ahmet.
- **5. Grammars:** the inventory table, total native and wasm size, and the plain-text list, with licences
  flagged (anything not MIT/Apache/BSD).
- **6. Changes to the spec:** each one as a concrete edit to
  `docs/superpowers/specs/2026-09-25-native-editor-design.md`.

Apply section 6's edits to the spec in the same commit.

- [ ] **Step 4: Delete the spike module**

```bash
git rm -r apps/editor-spike
# and remove the ":editor-spike" include + its comment from apps/settings.gradle.kts
```
Keep the inventory script and the probe app by moving them to `docs/superpowers/notes/m0-artifacts/`
first if M2 or M3 will reuse them. They are small.

- [ ] **Step 5: Commit**

```bash
git add -A docs apps/settings.gradle.kts
git commit -m "docs(editor): M0 risk-check results; spec updated; spike removed"
```

- [ ] **Step 6: Report to Ahmet** through the reply tool: a one-screen summary of sections 1–6 and whether
  the spec's plan survives unchanged.
