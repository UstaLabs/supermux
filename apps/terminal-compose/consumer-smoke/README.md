# consumer-smoke — can a stranger draw a terminal with the published pair?

A **separate Gradle build** (its own `settings.gradle.kts`; it is not in `apps/settings.gradle.kts`
and shares no project, source or classpath with supermux) whose only supermux dependency is

```
dev.supermux.terminal:terminal-compose:0.1.0-dev.1
```

`terminal-core` arrives with it, transitively, because the surface declares it `api` — and that is
itself part of what this build proves: a consumer names the *surface* and gets the *engine*.

Both are resolved from the two local test repositories that

```
:terminal-core:publishAllPublicationsToLocalTestRepository     -> terminal-core/build/test-repository
:terminal-compose:publishAllPublicationsToLocalTestRepository  -> terminal-compose/build/test-repository
```

write. `dev.supermux.terminal` may be served **only** by those two (`content { includeGroup(…) }`)
and Maven Central / Google may serve everything *except* that group (`excludeGroup`), so a green run
cannot have quietly used a project dependency, `:shared`, `:ui`, a cached snapshot or a remote
artifact. There is no Zig, no Android NDK and no C compiler anywhere in this build: if the published
artifacts do not carry a working engine and a usable surface, these checks fail.

## Run it

```sh
cd apps
./gradlew :terminal-core:publishAllPublicationsToLocalTestRepository
./gradlew :terminal-compose:publishAllPublicationsToLocalTestRepository
./gradlew -p terminal-compose/consumer-smoke jvmTest
```

`-PterminalCoreRepo=<dir>` / `-PterminalComposeRepo=<dir>` point the build at other repository
directories.

## What each check proves

| check | proves | runs here |
|---|---|---|
| `theSurfaceComesFromThePublishedJar` | `dev.supermux.terminal.compose.TerminalKt` (the `Terminal` composable's file facade) loads **by name** out of `terminal-compose-jvm-<version>.jar` in the local test repository, and `TerminalSession` out of `terminal-core-jvm-<version>.jar` — the engine came in transitively | yes |
| `noPartOfSupermuxIsOnTheClasspath` | no `terminal-compose/src`, `terminal-core/build`, `/shared/`, `/ui/` or `supermux-apps` entry is on the classpath, and `-Dsupermux.terminal.nativeLibrary` (the dev override) is unset. **Tripwire, not proof**: Gradle can hand the JVM one synthetic jar whose manifest carries the real `Class-Path` | yes |
| `theLicenceRidesInsideThePublishedJar` | `META-INF/dev.supermux.terminal/LICENSE` is a `jar:file:` resource of the published artifact | yes |
| `theSurfaceDrawsASessionRunOnThePackagedEngine` | the composable really draws: bytes fed to a `TerminalSession` appear in the surface's semantics, and `/proc/self/maps` shows the only `supermux_terminal` mapping is the library the PUBLISHED jar extracted into **this build's** cache directory | yes |
| `aHostThemesTheSurfaceWithoutAnySupermuxType` | a host themes the terminal with `TerminalTheme` alone — no app theme, no adapter, no `CompositionLocal` of ours | yes |
| `theSurfaceReportsTheGridItSettledOn` | the surface measures its box and resizes the session off the size it was opened with, reporting a cell size with it | yes |

The first check does not read `java.class.path` at all — a class's code source is taken from what
the JVM really loaded — so the synthetic-classpath caveat above does not weaken it.

## Not covered here

- **Android, iOS and the browser.** `terminal-core/consumer-smoke` already resolves the engine's
  AAR, iOS klib and wasm klib from published coordinates; the surface adds no native payload of its
  own to any of them, so the packaging question is answered there. What is *not* answered anywhere
  yet is a published-artifact UI run on a device — see `apps/terminal-sample/README.md`.
- **Apple publications.** A publish made on Linux contains none (their Kotlin targets are disabled
  on this host); the Apple artifacts come from a Mac-made publish, exactly as for `terminal-core`
  (`terminal-core/VERIFICATION.md` §4).
