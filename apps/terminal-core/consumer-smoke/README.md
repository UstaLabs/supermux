# consumer-smoke — can a stranger use the published package?

A **separate Gradle build** (its own `settings.gradle.kts`; it is not in `apps/settings.gradle.kts`
and shares no project, source or classpath with supermux) whose only dependency is

```
dev.supermux.terminal:terminal-core:0.1.0-dev.1
```

resolved from the local test repository that `:terminal-core:publishAllPublicationsToLocalTestRepository`
writes to `apps/terminal-core/build/test-repository`. There is no Zig, no Android NDK, no C
compiler and no `native/build.sh` anywhere in this build: if the published artifacts do not carry a
working engine, these checks fail.

`dev.supermux.terminal` may be served **only** by that repository (`content { includeGroup(...) }`),
and Maven Central / Google may serve everything *except* that group (`excludeGroup`), so a green run
cannot have quietly used a project dependency, a cached snapshot or a remote artifact.

## Run it

```sh
# 1. publish (from apps/), 2. consume
./gradlew :terminal-core:publishAllPublicationsToLocalTestRepository
./gradlew -p terminal-core/consumer-smoke jvmTest            # this host
./gradlew -p terminal-core/consumer-smoke wasmJsBrowserTest  # needs a WasmGC Chrome (CHROME_BIN)
./gradlew -p terminal-core/consumer-smoke iosSimulatorArm64Test   # Mac only, Mac-made publish
```

`-PterminalCoreRepo=<dir>` points the build at another repository directory.

## What each check proves

| check | source set | proves | runs here |
|---|---|---|---|
| `PackagedJvmEngineTest.theApiComesFromThePublishedJar` | jvmTest | the classes come from `terminal-core-jvm-<version>.jar` in the test repository, not from a project | yes |
| `…noPartOfTheTerminalCoreBuildTreeIsOnTheClasspath` | jvmTest | no `terminal-core/src`, `build/gradle`, `build/native` or `build/wasm` entry is on the classpath, and `-Dsupermux.terminal.nativeLibrary` (the dev override) is unset. **Tripwire, not proof**: Gradle can hand the JVM one synthetic jar whose manifest carries the real `Class-Path`, and then `java.class.path` shows only that jar | yes |
| `…theNativeLibraryIsAPackagedResource` | jvmTest | the JNI library is a `jar:file:` resource of that jar and its bytes match the packaged `native.properties` (sha256 + size + ABI + version) | yes |
| `…engineRunsTheSemanticFixtureFromTheExtractedPackagedLibrary` | jvmTest | the semantic fixture passes, and `/proc/self/maps` shows the ONLY `supermux_terminal` mapping is the file the loader extracted into this build's `build/terminal-native-cache/supermux-terminal/<version>/<sha256>/` | yes |
| `PackagedAndroidArtifactTest` | jvmTest | the `…-android` AAR resolves by coordinates and carries `jni/arm64-v8a` + `jni/x86_64`, `classes.jar` with the Android binding, and the licence files. **Packaging only** — nothing Android is executed | yes |
| `PackagedWasmEngineTest` | wasmJsTest | `TerminalRuntime.initialize()` + the semantic fixture in headless Chrome, against the `supermux-terminal.wasm` unpacked from the published klib | yes |
| `PackagedIosEngineTest` | iosTest | the fixture on the iOS simulator, linked against the static archive inside the published Apple klib | no — Mac only, and needs a publish made on a Mac |

The other three JVM checks do not read `java.class.path` at all — a class's code source, the
`jar:file:` URL of the packaged resource and the `/proc/self/maps` mapping are all taken from what
the JVM really loaded — so the synthetic-classpath caveat above does not weaken them.

The fixture itself (`ConsumerFixture`, commonMain) is the same red-cell / wide-char / CSI 6n /
resize sequence terminal-core's own `EngineContractTest` uses, so a green run means the *packaged*
library behaves like the one the package tests. It lives in `commonMain` on purpose: compiling it
proves the published **metadata** klib is usable from common code, not only from one platform.

## Known consumer requirement: the browser needs the two wasm files re-exported

Measured 2026-09-22: the Kotlin/Wasm toolchain does **not** copy a *dependency* klib's resources
next to the consumer's compiled module. A plain `wasmJs` consumer therefore fails at bundling with

```
Module not found: Error: Can't resolve './terminal-loader.mjs'
```

The fix (this build does it, and every host app must) is to re-export the two files terminal-core
ships inside `terminal-core-wasm-js-<version>.klib` as the app's own wasmJs resources:

```kotlin
val terminalCoreWasmKlib by configurations.creating { isTransitive = false }
dependencies { terminalCoreWasmKlib("dev.supermux.terminal:terminal-core-wasm-js:<version>@klib") }
val extractTerminalWasmAssets by tasks.registering(Copy::class) {
    from(terminalCoreWasmKlib.map { zipTree(it) }) { include("terminal-loader.mjs", "supermux-terminal.wasm") }
    into(layout.buildDirectory.dir("generated/terminalWasmAssets"))
}
kotlin.sourceSets.getByName("wasmJsMain").resources.srcDir(extractTerminalWasmAssets)
```

The bytes still come out of the published klib — never out of `terminal-core/build/wasm`.
