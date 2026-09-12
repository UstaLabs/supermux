# Web → KMP, Plan 5 of 5: cut-over — CMP 1.12, Playwright, CI/Docker/scripts, delete Vue

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The Kotlin/Wasm client is the only web client. Compose Multiplatform is bumped to 1.12.0 (ABI-safe on Kotlin 2.3.21; fixes the 0×0 accessibility root and several web input bugs), the broker's static serving covers every file the wasm build emits, the CodeMirror bundle has its own lockfile, the four Playwright journeys run against the wasm app, every build/CI/deploy path builds the bundle with Gradle instead of Vite, and `src/web-app` is deleted with every dangling reference swept.

**Architecture:** No new runtime code. The work is a dependency bump, broker MIME/caching polish, test-id alignment (`testTag` → DOM `id` through Compose-for-Web's accessibility mirror), a Playwright rewrite that mirrors the existing Maestro journey, and pipeline edits. A multi-stage Dockerfile keeps the JDK out of the runtime image.

**Spec:** `docs/superpowers/specs/2026-09-11-web-to-kmp-compose-design.md` §8, §9, §10 step 5. Results + carry-forwards of plans 1–4.

**Facts (verified 2026-09-12 — do not re-derive):**
- **CMP 1.12.0**: `ui-wasm-js-1.12.0.klib` manifest `abi_version=2.3.0`, `compiler_version=2.3.20` — byte-identical header fields to 1.11.1 → consumable by Kotlin 2.3.21. Its `ui` module requires `org.jetbrains.androidx.navigationevent:navigationevent-compose 1.1.0` (catalog pins 1.0.1 → bump). Release notes: `div#cmp_a11y_root` no longer 0×0 (sized to the canvas, synced on resize); wheel both axes; pointercancel; mobile keyboard in focused fields; tofu/fallback fonts + auto Noto; a11y crash fix; `NativeCanvas`/`NativePaint` deprecations become ERROR (grep `apps/` before bumping); `ComposeUiTest` idle handling changed (jvmTest timing); desktop `SwingPanel.background` deprecated; bundled hot-reload 1.2.0 (already ours). Not in 1.12: any `HtmlElementView` interop-hole fix (keep the known-issue note unless measured otherwise). Catalog: `apps/gradle/libs.versions.toml` `composeMultiplatform` :21, `navigationEvent` :31, `coil` 3.4.0 :49 and `composeMediaPlayer` 0.10.0 :57 stay (Kotlin 2.3 ABI pins).
- **Compose-for-Web a11y DOM** (verified in the 1.11.1 sources): `isA11YEnabled` defaults **true**; `ComposeViewport(document.body!!) { … }` uses defaults. The shadow root is OPEN (Playwright pierces it). Semantics → DOM: `testTag` → `id`; `Text` → `innerText`; `contentDescription` → `aria-label`; roles button/checkbox/switch/radio/tab/img/menu/heading/textbox/list/grid → `role`; `OnClick` → a real DOM `click` listener; sync debounced ~100 ms. So `Modifier.testTag("x")` = `#x` / `[id="x"]` (attribute form for ids with `:`), `getByRole("button", {name})` and `getByText` work.
- **Test ids** — canonical `src/shared/test-ids.ts` mirrored in `apps/shared/.../ui/TestIds.kt` (`tests/test-ids-parity.test.ts:31` enforces the mirror; `:44–50` ALSO reads `src/web-app/src/views/SessionListView.vue` + `components/SessionRow.vue` by path → ENOENT the moment Vue is deleted). `:ui` sites: `session-list` only when workspaces are OFF — with workspaces on (the fixture: the broker self-heals the seeded session into a workspace, see `.maestro/pair-and-converse.yaml:62–65`) the list is `workspaces_list` with rows `workspace_row_<id>`; `chat-view` (ChatPanel.kt:469, ViewHost.kt:79); `composer-input` (Composer.kt:278); send is **`composer-send`** (Composer.kt:280, canonical says `composer-submit`); **no `chat-message` tag anywhere in `apps/`**; `composer-attach` (:279); staged chip `composer_staged_<name>` (:293); `new-session` (SessionsRail.kt:103). Maestro journey `.maestro/pair-and-converse.yaml` asserts the reply by TEXT `Fixture reply: <prompt>`.
- **Playwright**: no `playwright.config`; specs are Bun scripts under `tests/ui/` (`core-journey`, `composer-attachment`, `push-banner`, `voice-recorder`) using `tests/ui/fixture-env.ts` (`uiFixture()` env: `MUX_TEST_BASE_URL/PAIR_TOKEN/DEVICE_NAME/SESSION_ID/SESSION_NAME/BROWSER_BIN`, refuses non-127.0.0.1 and port 9898; `browserLaunchOptions()` headless + `--no-sandbox`). `scripts/test-broker.sh:56–58` builds Vue unless `MUX_TEST_SKIP_WEB_BUILD=1`; fake agent replies `Fixture reply: <content>`; broker ready = `/me` → 401. `voice-recorder.spec.ts:77–82` reads `globalThis.__cmuxPromptInput` (dead on wasm); `composer-attachment.spec.ts:45–46` uses `[data-testid="attachment-menu"]` + `menuitem` role (Compose emits no menuitem role); `push-banner.spec.ts:94` `text=Enable` + a sqlite assert on `push_subscriptions` (persistent context, notifications permission). Playwright's default incognito context disables the Push API — the push spec already uses `launchPersistentContext`.
- **CI** `.github/workflows/ci.yml`: web-app installs :109–111 and :152–154; `setup-node` :134–136 + `vue-tsc` :137–139 (delete); `ui` lane :143–166 (`command -v google-chrome || bunx playwright install --with-deps chromium`, `bun run test:ui`, log upload); JDK+Gradle pattern at :183–190 / :262–266 (`actions/setup-java@v4` temurin 17 + `gradle/actions/setup-gradle@v4`); paths filter :56–90 has no `web` entry (add `apps/web/**`, `apps/ui/**`, `apps/shared/**`, `apps/gradle/**`; outputs :48–53; the `ui` lane's `if` :143–147); header comment :23–26 promises a cheap `ui` lane; comment :103–104 mentions "441 test files span the broker AND src/web-app". `release.yml`: `build-binaries` :24–43 (ubuntu x64, ubuntu-arm, **macos-14**) has only checkout + setup-bun → needs JDK 17 + Gradle; `scripts/smoke-binary.sh:166` greps `assets/*.js` in the served index (still passes); `publish-website` :732–795 touches no web assets.
- **Dockerfile**: single stage `FROM oven/bun:1` (:19), apt `tmux git ca-certificates curl nodejs npm` (:26–34), `COPY src/web-app/package.json …` (:71), `RUN cd src/web-app && bun install` (:77), the Vite build block (:82–90), `MUX_WEB_PORT=8787`, serves the in-image `src/channels/web/static`. `.dockerignore:2–3` comment "built in-image". No JDK anywhere.
- **Scripts**: `scripts/build-binary.sh` :66–67 installs, :69–75 the 3-rung Vite ladder, :89 `generate-static-manifest.ts`, :93–97 `bun build --compile`; `scripts/generate-static-manifest.ts:13` error text says `cd src/web-app && bun run build`; **`src/types/assets.d.ts` lacks `*.ttf` `*.xml` `*.txt`** which the staged tree contains (6 fonts, 6 drawables, LICENSE.txt) → `bun run typecheck` breaks once the manifest is generated; `scripts/test-broker.sh:56–58`; `scripts/feel-deploy.sh:144` change map (`src/web-app/*|src/channels/web/static/*`) + :219–226 (node_modules symlink + `vite build`); `scripts/test-android.sh:95–99` (comment only); `scripts/generate-logo-assets.ts:32–35,104` Vue icon targets + `tests/logo-assets.test.ts:16–19,30,35` asserting them; `README.md:3` logo srcset; `SETUP.md:136–138`; `docs/docs/web-channel-setup.md:39,43`; `tsconfig.json:11` `exclude: ["src/web-app"]`; `package.json:12` `test:ui` runs only core-journey; devDeps only for Vue (`vue`, `pinia`, `@vueuse/core`, `marked`, `dompurify`, maybe `@codemirror/*` — check `src/` consumers first; `playwright` stays).
- **cm6 bundle**: `apps/android/codemirror/README.md:14–21` builds by symlinking `src/web-app/node_modules` into `/tmp/cmbuild` and `bun build cm6-entry.mjs --outfile apps/android/src/main/assets/editor/cm6.js --target browser --format iife --minify`. Direct deps to pin (from `src/web-app/bun.lock`): `@codemirror/view 6.43.0, state 6.6.0, commands 6.10.3, language 6.12.3, autocomplete 6.20.2, lint 6.9.6, search 6.7.0, theme-one-dark 6.1.3, lang-javascript 6.2.5, lang-python 6.2.1, lang-java 6.0.2, lang-cpp 6.0.3, lang-rust 6.0.2, lang-go 6.0.1, lang-php 6.0.2, lang-sql 6.10.0, lang-json 6.0.2, lang-markdown 6.5.0, lang-html 6.4.11, lang-css 6.3.1, lang-xml 6.1.0, lang-yaml 6.1.3, lang-vue 0.1.3, lang-wast 6.0.2, legacy-modes 6.5.3` (NOT lsp-client/merge/language-data/codemirror). `cm6-entry.test.ts` reads the entry as text — no node_modules needed. Only the root `bun.lock` is gitignored.
- **Broker static** (`src/channels/web/static-serve.ts`): `guessMime` :11–24 lacks `.ttf/.otf/.woff/.xml/.txt`; `COMPRESSIBLE` :26; gzip cache only for `/assets/` (:34) so `/editor/cm6.js` (1.3 MB) is re-gzipped per request; the cache entry's `mtime` field is written, never read; `SECURITY_HEADERS` :62. `check-bundle-size` dies with Vue; its replacement is `stageForBroker`'s 8 MiB guard.
- Known open web issue: CMP interop hole (32 px transparent band above `HtmlElementView` panes) — re-measure after the bump.

---

## File structure

| Area | Files |
|---|---|
| CMP bump | `apps/gradle/libs.versions.toml`; any `NativeCanvas`/`NativePaint`/`SwingPanel(background=)` sites; `apps/web/.../Main.kt` (a11y root id if useful). |
| Broker polish | `src/channels/web/static-serve.ts` (+ test), `src/types/assets.d.ts`, `scripts/generate-static-manifest.ts`. |
| cm6 | `apps/android/codemirror/{package.json,bun.lock,README.md}`, `tests/cm6-bundle-drift.test.ts` (optional). |
| Test ids + specs | `src/shared/test-ids.ts`, `apps/shared/.../ui/TestIds.kt`, `apps/ui/.../chat/Composer.kt` (send tag) or the canonical id, `apps/ui/.../chat/Timeline.kt` (message tags), `tests/test-ids-parity.test.ts`, `tests/ui/*.spec.ts`, `tests/ui/fixture-env.ts`, `package.json` (`test:ui`). |
| Pipeline | `scripts/build-binary.sh`, `scripts/test-broker.sh`, `scripts/feel-deploy.sh`, `scripts/test-android.sh` (comment), `scripts/generate-logo-assets.ts` + `tests/logo-assets.test.ts`, `Dockerfile`, `.dockerignore`, `.gitignore` (comment), `.github/workflows/{ci,release}.yml`, `tsconfig.json`, `package.json` (deps), `README.md`, `SETUP.md`, `docs/docs/web-channel-setup.md`. |
| Deletion | `src/web-app/**`, `src/channels/web/watch-session-row.ts:21` comment, doc-comment sweep in `apps/**` (replace `src/web-app/src/...` citations with "(retired Vue PWA)"). |

---

### Task 1: Compose Multiplatform 1.12.0

- [x] **Step 1:** `composeMultiplatform = "1.12.0"`, `navigationEvent = "1.1.0"` (KDoc the reason next to each). `grep -rn "NativeCanvas\|NativePaint\|SwingPanel(" apps/` — fix any ERROR-level deprecation the compiler reports (VncFramebuffer does raw pixels via Skia `Bitmap`, not `NativeCanvas` — verify).
- [x] **Step 2:** Build everything this host can: `./gradlew :shared:jvmTest :ui:jvmTest :desktop:test :android:testDebugUnitTest :shared:compileKotlinWasmJs :ui:compileKotlinWasmJs :web:wasmJsBrowserTest :web:stageForBroker` (jvm UI/desktop under `xvfb-run -a`; android needs `ANDROID_HOME` and the gitignored `apps/android/google-services.json` — copy it from the main checkout for the run and delete after, as plan 1 did). Record counts and any timing-related jvmTest failures (the release notes changed `ComposeUiTest` idling); fix real breakages, note flakes. Apple targets can't be compiled here — say so.
- [x] **Step 3:** Browser check on a hermetic broker: the app loads (no pageerror), `document.querySelector` finds `#cmp_a11y_root` (or whatever id 1.12 gives) with non-zero size, `#composer-input` exists in the shadow DOM; re-measure the interop hole with a terminal mounted (magenta body → pixel scan; record whether the 32 px band is gone). Screenshot to scratchpad `plan5/`.
- [x] **Step 4:** Commit `build(apps): Compose Multiplatform 1.12.0 (+ navigationevent 1.1.0)`.

### Task 2: Broker static serving covers the wasm tree

- [x] **Step 1 (red):** `static-serve.test.ts`: `.ttf → font/ttf` + gzip, `.xml → application/xml`, `.txt → text/plain; charset=utf-8`, `.otf/.woff`; `/editor/cm6.js` served gzipped from the cache on the second request (spy: `Bun.gzipSync` call count, or timing-free: assert the cache map via an exported `_gzipCacheSize()` test hook) and the cache invalidates when the file's mtime changes.
- [x] **Step 2 (green):** add the MIME lines, extend `COMPRESSIBLE` (`ttf|otf|xml|txt`), make the gzip cache key include `mtimeMs` and cache `/editor/` too (read the `mtime` field that is currently written and ignored). Add `*.ttf *.otf *.woff *.xml *.txt` to `src/types/assets.d.ts`; fix `generate-static-manifest.ts:13`'s message to `cd apps && ./gradlew :web:stageForBroker`.
- [x] **Step 3:** `bun test src/channels/web` green; generate the manifest once against the staged tree and run `bun run typecheck` (then `git checkout -- src/channels/web/static-manifest.generated.ts`). Commit `feat(web-channel): fonts/xml/txt MIME, gzip cache for /editor, manifest typings for the wasm tree`.

### Task 3: cm6 bundle stands alone

- [x] **Step 1:** `apps/android/codemirror/package.json` (the pinned list above; `"build"` script writing `../src/main/assets/editor/cm6.js` with `--target browser --format iife --minify`) + `bun install` → commit `bun.lock`. Rewrite README.md build section (no symlink, no `src/web-app`). Update the entry header comment.
- [x] **Step 2:** Rebuild and diff: `cd apps/android/codemirror && bun run build && git diff --stat apps/android/src/main/assets/editor/cm6.js`. If the bundle changes only by the version pins (bytes differ), rebuild is the new truth — run `:desktop:test` (`EditorWebAssetsTest`) and the plan-3 editor browser check (open/edit/save) on a restaged bundle before accepting; if it is byte-identical, say so. Add `tests/cm6-bundle-drift.test.ts` (bun) that rebuilds into a temp dir and asserts byte-equality with the committed bundle (skip when `bun install` deps are absent, with a clear message).
- [x] **Step 3:** Commit `build(editor): standalone package + lockfile for the committed CodeMirror bundle`.

### Task 4: Test ids + Playwright journeys against the wasm app

- [x] **Step 1: ids.** Decide once and mirror in both `test-ids.ts` and `TestIds.kt` (keep `tests/test-ids-parity.test.ts:31` green): canonical send id becomes `composer-send` (matches `:ui`; drop `composer-submit`); add `chat-message` as a PREFIX id `chat-message:<direction>:<messageId>` applied via `Modifier.testTag` on each timeline row in `apps/ui/.../chat/Timeline.kt` (find the per-message composable; direction from the message's role; add `TestIds.chatMessage(direction, id)` + a jvm test that the tag is present); keep `session-list`/`session-row` (workspaces-off) and document `workspaces_list`/`workspace_row_<id>` as the workspaces-on ids. Rewrite `tests/test-ids-parity.test.ts:44–50` to assert the Kotlin sites (`SessionListScreen.kt` uses `TestIds.SESSION_LIST`, `SessionRow.kt` uses `TestIds.sessionRow`, `Timeline.kt` uses `chatMessage`) instead of the Vue files.
- [x] **Step 2: specs.** Port all four under `tests/ui/` to the a11y DOM: selectors `#chat-view`, `#composer-input` (type via `page.keyboard.type` after clicking it; assert via `innerText`), `#composer-send`, `[id^="workspace_row_"]` (or `#session-list`/`[id^="session-row:"]` when the fixture has no workspace — detect either), `getByText("Fixture reply: …")` AND `[id^="chat-message:outbound:"]`; keep the `/sessions/<id>/messages` persistence assert. `composer-attachment`: click `#composer-attach` (pointer mode picks directly — see plan 3 Results), `page.waitForEvent("filechooser")` → set a temp file → chip `[id^="composer_staged_"]` → send → persisted attachment. `voice-recorder`: fake media flags, `#composer-mic` (find the real tag/aria-label in Composer.kt), stub `**/transcribe`, assert the draft text via `#composer-input` innerText. `push-banner`: persistent context, grant notifications, `getByText("Enable")`, then the sqlite assert. Add `MUX_TEST_A11Y_ROOT` no; instead a shared helper `tests/ui/compose-dom.ts` (`byTag(page, id)`, `typeInto(page, id, text)`, `waitReady(page)` = wait for `#composer-input` or `#workspaces_list`). `package.json` `test:ui` runs all four specs sequentially (each spawns its own fixture via test-broker.sh — or one fixture + four specs; pick the cheaper and say which).
- [x] **Step 3:** `scripts/test-broker.sh:56–58` → `( cd apps && ./gradlew :web:stageForBroker --console=plain )` unless `MUX_TEST_SKIP_WEB_BUILD=1` (keep the flag; `test-android.sh` comment updated: skipping now saves minutes). Run `bun run test:ui` locally (Chrome at `/usr/bin/google-chrome`) → all four PASS lines. Commit `test(ui): Playwright journeys drive the Compose-for-Web app through its accessibility DOM`.

### Task 5: Pipelines — build-binary, Docker, CI, release, deploy, docs

- [x] **Step 1: `scripts/build-binary.sh`** — replace :66–75 with a JDK precondition (`command -v java || die "needs JDK 17+ for :web:stageForBroker"`) and `( cd apps && ./gradlew :web:stageForBroker --no-daemon --console=plain )`; update the header ladder (:8–19). Run it locally end to end (`scripts/build-binary.sh dist/supermux-linux-x64 dev <sha>`) and `scripts/smoke-binary.sh` on the output.
- [x] **Step 2: Dockerfile** — multi-stage: `FROM eclipse-temurin:17-jdk AS webbuild` copies `apps/` (+ `src/channels/web/static-serve.ts` because `stageForBroker` sanity-checks its sibling — copy the minimal `src/channels/web/` tree or relax the check to the dir name; prefer copying the file) and runs `./gradlew :web:stageForBroker --no-daemon`; the runtime stage drops :71, :77, :82–90 and does `COPY --from=webbuild /src/src/channels/web/static ./src/channels/web/static`. `.dockerignore`: refresh the comment, add `apps/*/build`, `apps/.gradle`, `apps/kotlin-js-store` stays included. `docker build .` locally must succeed (needs network); note image size delta.
- [x] **Step 3: CI** — `ci.yml`: delete the two `src/web-app` installs, `setup-node`, `Typecheck PWA`; `ui` lane gets `setup-java` 17 + `setup-gradle` and runs `bun run test:ui` (which now stages via Gradle); add the `web` paths filter + output + `if`; fix the :23–26 and :103–104 comments (the lane is no longer cheap: gate it on `web || ts` changes and rely on the Gradle cache). `release.yml` `build-binaries`: add `setup-java` 17 + `setup-gradle` to the matrix job (incl. macos-14). Validate YAML (`bunx yaml-lint` or `actionlint` if present; else careful review).
- [x] **Step 4: deploy + misc** — `scripts/feel-deploy.sh` :144 change map → `apps/web/*|apps/ui/*|apps/shared/*|src/channels/web/static/*`, :219–226 → Gradle stage (share `GRADLE_USER_HOME`/`apps/build/wasm/node_modules` with the main checkout via symlink like the old node_modules trick); `scripts/generate-logo-assets.ts` targets → `apps/web/pwa/icons/*` + `apps/web/pwa/favicon.ico` (drop the Vue rows) and `tests/logo-assets.test.ts` in lockstep; `tsconfig.json` remove the exclude; `package.json` prune devDeps proven unused by `grep -rn "from \"vue\"\|pinia\|@vueuse\|marked\|dompurify" src tests scripts`; `README.md:3` srcset → `apps/web/pwa/icons/icon-512.png`; `SETUP.md:136–138` → `cd apps && ./gradlew :web:stageForBroker` + JDK 17 prerequisite; `docs/docs/web-channel-setup.md:39,43` (voice max seconds: point at the Kotlin `WebMic` constant or drop); `.gitignore:9–10` comment.
- [x] **Step 5:** `bun test` (root — the whole suite, `.mux/verify.sh`), `bun run typecheck`; commit `build(web): Gradle-staged bundle in build-binary, Docker (JDK build stage), CI ui lane, release binaries, feel-deploy; docs`.

### Task 6: Delete `src/web-app` + sweep + Results

- [x] **Step 1:** `git rm -r src/web-app`; fix `src/channels/web/watch-session-row.ts:21`; sweep `grep -rn "src/web-app" --include=*.kt --include=*.ts --include=*.mjs --include=*.swift --include=*.xml --include=*.md apps src scripts tests README.md SETUP.md` → replace citations with "(retired Vue PWA; see git history before 2026-09-12)" in comments; leave dated docs under `docs/docs/**` untouched. `apps/android/codemirror/cm6-entry.mjs` header: "mirrors the retired web CodeEditor setup".
- [x] **Step 2:** Full verification: `bun test` (verify.sh), `bun run typecheck`, `bun run test:ui` (four journeys), `./gradlew :shared:jvmTest :ui:jvmTest :desktop:test :web:wasmJsBrowserTest :web:stageForBroker` green, `scripts/build-binary.sh` + `smoke-binary.sh` green, `docker build` green. `grep -rn "web-app" . --exclude-dir=node_modules --exclude-dir=docs --exclude-dir=.git` → only the `.gitignore`/historical hits you intend.
- [x] **Step 3:** `## Results` in this plan (CMP bump outcome incl. the interop-hole re-measure, journey timings, image size, binary size, bundle size) + a "What changed for operators" paragraph (JDK 17 needed to build; Docker build stage). Commit `chore(web): retire the Vue PWA — the Compose-for-Web client is the only web client`.
- [ ] **Step 4 (coordinator):** update `~/.mux/domains/claudemux.md` (the client matrix: Web = `apps/web` Kotlin/Wasm; Vue dead) and hand the branch to `superpowers:finishing-a-development-branch`.

---

### Task 1 outcome (blocked on AGP 9.1 / compileSdk 37)

**CMP 1.12.0 is parked, not landed.** The bump is correct and green on every target this host can
build — and blocked on Android by a toolchain requirement the plan did not anticipate.

**What 1.12 requires on Android.** CMP 1.12.0 resolves the androidx Compose artifacts to 1.12.0, and
`:android:checkDebugAarMetadata` rejects all of them (`ui-android`, `ui-graphics-android`,
`ui-text-android`, `ui-tooling(-data)-android`, `foundation(-layout)-android`,
`animation(-core)-android`, `runtime-saveable-android`) with two hard constraints:

- *"requires Android Gradle plugin **9.1.0** or higher. This build currently uses Android Gradle
  plugin 8.9.1."*
- *"requires libraries and applications that depend on it to compile against version **37** or later
  of the Android APIs. `:android` is currently compiled against android-36."*

That is AGP 8.9.1 → 9.1.0, Gradle 8.14 → 9.x, `androidCompileSdk` 36 → 37 — a migration of its own,
not a step of this plan, and AAR metadata has no supported suppression for `minAgpVersion` /
`minCompileSdk`. **Land the Android toolchain bump first, then replay the patch below.**

**The patch.** Six files, 174 lines, verified with `git apply --check` against this commit's tree:

`<scratchpad>/plan5/cmp-1.12.0.patch` (session scratchpad —
`/tmp/claude-1000/-home-ahmet--mux-worktrees-supermux-3962b5bf-add67966-f459-4473-ab17-b64a62eb8da2/4bdce31a-5d27-443a-b3cf-06204998acf5/scratchpad/plan5/cmp-1.12.0.patch`)

1. `apps/gradle/libs.versions.toml` — `composeMultiplatform 1.11.1 → 1.12.0`,
   `navigationEvent 1.0.1 → 1.1.0`, both with their reason comments.
2. `SettingsHubTest.kt`, `VoiceSettingsScreenTest.kt`, `SupermuxAppNavTest.kt` — **compile error**:
   `androidx.compose.ui.backhandler.LocalCompatNavigationEventDispatcherOwner` is gone in 1.12 (so is
   `ui-desktop`'s internal `LocalInternalNavigationEventDispatcherOwner`); 1.12's `BackHandler` reads
   the public `androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner` instead. Import
   swap only — `provides` syntax is unchanged.
3. `DragReorderTest.kt` — a **real** 1.12 idling regression, not the known flake (passes on 1.11.1,
   hangs 3/3 on 1.12.0 in isolation). `DragReorder.startEdgeScroll()`'s
   `while (draggingKey != null) { … delay(16) }` is live while the finger is down; with an
   auto-advancing clock that delay re-arms forever, so every mid-drag `onNodeWithTag` (each waits for
   idle) times out. Fix: `mainClock.autoAdvance = false` before the `down()`, two frames by hand.
   No production code changed.
4. `ProxiesSettingsScreenTest.kt` — a timing shift; one test lacked the
   `waitUntil { assertIsDisplayed }` guard its two siblings have. Added.

`grep NativeCanvas|NativePaint` over `apps/`: **zero hits** (VncFramebuffer confirmed raw Skia
`Bitmap`). `SwingPanel(background=)` is WARNING-only in 1.12 (5 `:desktop` probe sites) — no edit
forced. New WARNING to schedule: `runComposeUiTest` is deprecated in favour of
`androidx.compose.ui.test.v2.runComposeUiTest` (~950 warnings in `:ui:jvmTest`).

**Green under the patch:** `:shared:jvmTest` 985 pass / 1 skipped · `:ui:jvmTest` 1613 pass ·
`:desktop:test` 297 pass · `:shared:compileKotlinWasmJs` + `:ui:compileKotlinWasmJs` ·
`:web:wasmJsBrowserTest` 65 pass · `:web:stageForBroker` (11 m). Apple targets cannot be compiled on
this Linux host (Kotlin/Native ios*/uikit* need a macOS konan host) — untested.

**Accessibility DOM — measured on 1.12.0, and it decides Task 4.** Hermetic `test-broker.sh` fixture,
Chrome via Playwright, no `pageerror`, no console errors. `ComposeViewport(document.body!!)` with
**defaults** — no explicit `isA11YEnabled = true` needed.

- **`div#cmp_a11y_root` is `1280×900`** (`role="presentation"`) — the 0×0 bug is fixed. It lives
  inside the OPEN shadow root on `<body>`'s single anonymous `<div>`: Playwright locators pierce it,
  but `page.evaluate(() => document.querySelector("#cmp_a11y_root"))` returns **null**. Any in-page
  assertion helper must walk `.shadowRoot` itself.
- **`testTag` → element `id` works**, with roles and `aria-label`s populated. Confirmed present:
  `#workspaces_list` (`role=list`), `[id^="workspace_row_"]` (`role=button`), `#new_session_row`,
  `#sidebar_{search,group_toggle,footer,footer_theme,footer_usage,footer_devices,footer_settings,divider}`,
  `#list_overflow`, `#view-tab-strip`, `#tab-add-view`, `#tab-add-view-{terminal,diff,display}`,
  `#view-tab-<id>`, `#tab-close-<id>`, `#view_chat`, `#chat_body`, `#chat_starter_0..2`,
  `#composer-card`, **`#composer-input`** (`role=textbox`), `#composer-attach`, `#composer-mic`,
  **`#composer-send`**, `#composer-model-pill`, `#composer-reasoning-pill`, `#footer_detail`.
- **⚠️ Playwright's `.click()` is REFUSED on every mirror element** — *"`<canvas … role="generic">`
  intercepts pointer events"*, 30 s timeout, every time. **`locator.dispatchEvent("click")` works**
  (Compose installs a real DOM `click` listener on `OnClick` nodes) and drove the whole journey;
  `click({ force: true })` is the alternative. Put this in `tests/ui/compose-dom.ts` as the only
  sanctioned tap, and never call `.click()` directly.
- **`#chat-view` does not exist** — the chat container is **`#view_chat`** / `#chat_body`. The Facts
  section above is wrong on this; fix the selector or add a `chat-view` testTag in Task 4 Step 1.
- `composer-submit` does not exist anywhere; `composer-send` is the real id (Task 4 Step 1's choice is
  correct). **No timeline row carries any id yet** — `chat-message:<direction>:<id>` must be added
  before a spec can assert on it. The fixture is workspaces-on, so `session-list` / `session-row`
  never appear, and the broker is already onboarded, so `setup_wizard` never appears either.
- The a11y sync is debounced ~100 ms and the wasm boot is slow: ~6 s after `/pair` before the sidebar
  ids appear. `waitReady(page)` should poll `#workspaces_list`, not sleep.

**Interop hole: STILL PRESENT in 1.12.0.** Terminal mounted for real (fixture `tmux` stub replaced
with `/usr/bin/tmux`); xterm at `(320, 32) 960×855`. With `document.body.style.background =
"#ff00ff"`, every scanline from `y=0` to `y=31` above it is dominantly `rgb(255,0,255)` — the 32 px
band where the view tab strip should paint is fully transparent. **Keep the known-issue note.**

**Bundle size:** staged `src/channels/web/static` 22,333,188 → 22,797,721 B (**+464,533 B, +2.08 %**);
`stageForBroker` gzip total 6269 KB (8 MiB guard untroubled). `supermux-apps-web-*.wasm` 10,942,205
raw / 2,862,494 gz; `skiko-*.wasm` 8,640,316 / 3,328,934; `app-*.js` 993,828 / 211,379.

---

### Toolchain bump outcome (Task 1 UNBLOCKED — CMP 1.12.0 is landed)

**Status: green.** The Android toolchain bump that Task 1 was blocked on is done, and the parked
CMP 1.12.0 patch is replayed on top of it. Everything this host can build is green.

**Final version set** (`apps/gradle/libs.versions.toml`, `apps/gradle/wrapper/…`,
`apps/gradle.properties`):

| key | before | after |
|---|---|---|
| `kotlin` | 2.3.21 | **2.4.10** |
| `agp` | 8.9.1 | **9.3.1** |
| Gradle wrapper | 8.14 | **9.7.0** |
| `androidCompileSdk` | 36 | **37** |
| `androidTargetSdk` | *(did not exist)* | **36** |
| `composeMultiplatform` | 1.11.1 | **1.12.0** |
| `navigationEvent` | 1.0.1 | **1.1.0** |
| `composeBom` | 2026.06.00 | **2026.09.00** |
| `googleServices` | 4.4.2 | **4.5.0** |
| `skie` | 0.10.12 | **0.10.14** |
| `coil` | 3.4.0 | **3.5.0** (Kotlin-2.3 ABI pin lifted) |
| `composeMediaPlayer` | 0.10.0 | **0.11.4** (Kotlin-2.3 ABI pin lifted) |

JDK stays 17. `composeHotReload` stays 1.2.0 — `:desktop` configures and tests fine on Gradle 9 /
Kotlin 2.4, no bump forced.

**Forced changes, and why each one was forced.**

1. **Kotlin is 2.4.10, NOT 2.4.20.** SKIE 0.10.14 — the newest release on Maven Central — hard-fails
   at configuration with *"SKIE 0.10.14 does not support Kotlin 2.4.20. Supported versions are
   [… 2.4.0, 2.4.10]"*. The only escapes are `skie { isEnabled = false }` (which would strip the
   Swift-friendly API `:ios` ships — a real regression) or 2.4.10. 2.4.10 configures and builds
   cleanly against AGP 9.3.1 / Gradle 9.7.0, so it is the pin. Raise `kotlin` and `skie` together.
2. **`android.newDsl=false` + `android.builtInKotlin=false`** in `apps/gradle.properties`. AGP 9
   defaults both on; `org.jetbrains.kotlin.android` (applied by `:android`) refuses to apply against
   the new DSL — *"class ApplicationExtensionImpl cannot be cast to BaseExtension"* — and AGP's own
   message names `android.newDsl=false` as the temporary bypass. TODO before AGP 10: drop KGP from
   `:android` and move to AGP's built-in Kotlin.
3. **`android.enableLegacyVariantApi` does NOT exist any more.** The plan expected to set it for the
   `com.android.library` + `kotlin.multiplatform` combination in `:shared`/`:ui`; AGP 9.3.1 fails
   the build outright (*"removed in version 9.0 … has no effect, use android.newDsl instead"*). The
   deprecated combination still works under `android.newDsl=false`; the migration to
   `com.android.kotlin.multiplatform.library` / `androidLibrary {}` is a TODO before AGP 10, noted
   in `gradle.properties`.
4. **`kotlin.daemon.jvmargs=-Xmx6g`.** Kotlin 2.4's Kotlin/Wasm backend needs materially more heap
   than 2.3 did. At the inherited `-Xmx2g`, `:web:compileProductionExecutableKotlinWasmJs` does not
   fail — it GC-thrashes indefinitely (measured with `jstat`: old gen pinned at 99.97 %, **1723 full
   GCs / 4176 s of GC**, still not finished after 80 minutes). With 6 g the same task plus
   `:web:wasmJsBrowserTest` and `:web:stageForBroker` finish in **14m13s** total. Anyone building
   `:web` needs the headroom — this is the single most likely CI/Docker surprise of the bump.
5. **`targetSdk` gets its own catalog key.** `apps/android/build.gradle.kts:40` read
   `targetSdk = libs.versions.androidCompileSdk`, so raising compileSdk to 37 would have silently
   opted the shipped app into API 37 behaviour changes. It now reads `androidTargetSdk` (36).
6. **The Android SDK platform is `platforms;android-37.0`, on the BETA channel.** Android SDK
   platforms are minor-versioned now. `sdkmanager "platforms;android-37"` fails with *"Failed to
   find package"*, and nothing above 36 appears on the default (stable) channel at all. The working
   install is `sdkmanager --channel=1 "platforms;android-37.0"`; AGP resolves `compileSdk = 37`
   against it without further configuration. `.github/workflows/{ci,release}.yml` use that exact
   command, and `ci.yml`'s `kotlin` and `android-journey` lanes gained
   `android-actions/setup-android@v3` + the install step (they previously had none).
7. The four `:ui` jvmTest fixes from the parked patch, unchanged and still all that CMP 1.12 needs:
   `LocalCompatNavigationEventDispatcherOwner` → `androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner`
   (`SettingsHubTest`, `VoiceSettingsScreenTest`, `SupermuxAppNavTest`); `mainClock.autoAdvance =
   false` before `down()` in `DragReorderTest`; the missing `waitUntil` guard in
   `ProxiesSettingsScreenTest`. **No production source file was changed by this bump at all.**

`grep NativeCanvas|NativePaint` over `apps/` is still zero hits. New Kotlin 2.4 / CMP 1.12 warnings
(none forced an edit, all scheduled): `BackHandler`/`PredictiveBackHandler` deprecated in favour of
`NavigationEventHandler`; `runComposeUiTest` deprecated in favour of the `…test.v2` one;
`ScrollableTabRow` → `PrimaryScrollableTabRow`; several `Icons.Outlined.*` → `Icons.AutoMirrored.*`.
Gradle 9.6 API deprecations in `apps/desktop/build.gradle.kts` and `apps/ios/build.gradle.kts`
(`tasks.registering` delegates) warn but build; Gradle 10 will need them rewritten.

**Suite counts** (all zero failures):

| suite | result |
|---|---|
| `./gradlew --version` | Gradle **9.7.0**, Kotlin 2.4.0, launcher JVM 17.0.20 |
| `:shared:jvmTest` | **985** pass, 1 skipped |
| `:ui:jvmTest` (xvfb) | **1613** pass |
| `:desktop:test` (xvfb) | **297** pass |
| `:android:testDebugUnitTest` | **90** pass |
| `:android:assembleDebug` | APK produced, 38,327,779 B |
| `:shared:compileKotlinWasmJs`, `:ui:compileKotlinWasmJs` | compile clean |
| `:web:wasmJsBrowserTest` | **65** pass |
| `:web:stageForBroker` | 3 hashed assets, gzip total **6376 KB** (8 MiB guard fine) |

Staged `src/channels/web/static` is **23,078,759 B** (was 22,797,721 B on CMP 1.12 + Kotlin 2.3.21,
22,333,188 B on 1.11.1) — **+280,038 B / +1.2 %** attributable to Kotlin 2.4 codegen.

**Accessibility DOM — re-measured after the bump, unchanged from the parked measurement.** Hermetic
`scripts/test-broker.sh` fixture, headless Chrome, 1280×900: no `pageerror`, no `console.error`.
`div#cmp_a11y_root` is **1280×900** `role="presentation"` (the 0×0 bug stays fixed);
`#workspaces_list`, `[id^="workspace_row_"]`, `#composer-input`, `#composer-send`, `#composer-attach`
all present; `#tab-add-view` → `#tab-add-view-terminal` opens a terminal and a **real tmux** (the
fixture's stub replaced with `/usr/bin/tmux`) mounts — 2 xterm nodes, prompt renders. Everything
Task 4 was told still holds, including the two rules that matter most: **`locator.click()` is
refused on mirror elements** (the canvas intercepts pointer events) so `dispatchEvent("click")` is
the only sanctioned tap, and `page.evaluate(document.querySelector(...))` returns null because the
mirror lives in an open shadow root — Playwright locators pierce it, in-page code must walk
`.shadowRoot`. **The 32 px interop hole above `HtmlElementView` panes is still present** (screenshot:
the terminal starts at y=0 and the view tab strip does not paint). Screenshots in the session
scratchpad: `plan5/bump-01-chat.png`, `plan5/bump-02-terminal.png`.

**Docker: GREEN, no change needed.** `docker build -t supermux-web-cutover .` succeeded unmodified
(`webbuild` stage 24m16s, image 3.58 GB). The feared AGP 9 configuration-time Android SDK
requirement did NOT materialise: `:web:stageForBroker` configures `:web`, `:ui` and `:shared`, and
even though `:shared`/`:ui` apply `com.android.library`, AGP 9.3.1 configures them without an SDK
present as long as no Android task is requested — so no `-Pandroid.skip` guard, no `ANDROID_HOME`,
and no cmdline-tools in the build stage. Note the stage inherits `kotlin.daemon.jvmargs=-Xmx6g` from
`apps/gradle.properties`: `compileProductionExecutableKotlinWasmJs` took 171 s there, and a Docker
daemon capped below ~8 GB would regress to the GC thrash described in item 4.

**Apple caveat:** iOS / uikit targets **cannot be compiled on this Linux host** (Kotlin/Native
`ios*`/`uikit*` need a macOS konan host), so `:ios` and the Apple source sets of `:shared`/`:ui` are
CONFIGURED but UNTESTED under Kotlin 2.4.10 / SKIE 0.10.14 / coil 3.5.0. The first macOS build after
this bump should be treated as a verification step. `scripts/test-android.sh` (the emulator journey)
was **not runnable**: no emulator is attached (`adb devices` empty) and no `emu` helper is on PATH.

**Carry-forwards for the rest of plan 5.**
- *Task 4 (Playwright rewrite):* the selector/interaction facts above are confirmed post-bump —
  `#view_chat`/`#chat_body` (not `#chat-view`), `composer-send` (not `composer-submit`),
  `workspaces_list`/`workspace_row_<id>` in the fixture, still **no id on any timeline row**,
  `dispatchEvent("click")` only, ~6 s wasm boot so poll, never sleep.
- *Any lane that builds `:web`:* budget the Kotlin daemon heap (item 4). CI runners and the Docker
  `webbuild` stage inherit `apps/gradle.properties`, so they get the 6 g automatically — but a
  machine with less than ~8 GB free will now OOM where it used to merely be slow.
- *Task 6 (deletion):* nothing in this bump touches `src/web-app`; the sweep is unaffected.

---

## Results (2026-09-12)

**Plan 5 is complete. `src/web-app` is gone; the Kotlin/Wasm Compose client is the only web
client.** Everything this Linux host can build or run is green.

### Per-task outcomes

| task | outcome |
|---|---|
| **1 — CMP 1.12.0** | Landed via the **toolchain bump** recorded above (it was blocked on AGP 9.1 / compileSdk 37). Final set: Kotlin **2.4.10**, AGP **9.3.1**, Gradle **9.7.0**, compileSdk **37** / targetSdk **36**, CMP **1.12.0**, navigationevent **1.1.0**, compose BOM 2026.09.00, SKIE 0.10.14, coil 3.5.0, composeMediaPlayer 0.11.4. No production source file changed; four `:ui` jvmTest fixes only. Kotlin 2.4's wasm backend forced `kotlin.daemon.jvmargs=-Xmx6g`. |
| **2 — broker static serving** | `f8587394` + `ffbf828c`. `guessMime` gained `.ttf/.otf/.woff/.woff2/.xml/.txt`; `COMPRESSIBLE` extended; the gzip cache is keyed by path **and** `mtimeMs` (so a redeploy replaces rather than accumulates) and now covers `/editor/` as well as `/assets/`, which stops the 1.3 MB `cm6.js` being re-gzipped on every request. `src/types/assets.d.ts` learned `*.ttf *.otf *.woff *.xml *.txt` so the generated manifest typechecks; `generate-static-manifest.ts`'s failure text points at `cd apps && ./gradlew :web:stageForBroker`. |
| **3 — cm6 bundle stands alone** | `f50304b4`. `apps/android/codemirror` has its own `package.json` + `bun.lock` pinning every direct `@codemirror/*` dep (including `@codemirror/lsp-client`, which the plan's list had missed — without it `bun build` walked up to the repo-root `node_modules`) and a `build` script that writes `../src/main/assets/editor/cm6.js` in place. No symlink into `src/web-app/node_modules` any more; `tests/cm6-bundle-drift.test.ts` rebuilds into a temp dir and asserts byte-equality (skipping with a clear message when the deps are not installed). |
| **4 — test ids + Playwright** | `46559524`. All four journeys now drive Compose-for-Web's accessibility mirror: `testTag` → element `id` inside an **open** shadow root. Three facts are encoded once in `tests/ui/compose-dom.ts`: the canvas intercepts pointer events so `locator.click()` always times out and `dispatchEvent("click")` is the only sanctioned tap; Playwright locators pierce the shadow root but `document.querySelector` inside `page.evaluate` does not; the mirror syncs on a ~100 ms debounce after a ~6 s wasm boot, so every wait polls. Ids: `composer-submit` never existed — canonical is now **`composer-send`**; timeline rows gained **`chat-message:<direction>:<messageId>`** (they carried no id at all), which is what lets the core journey prove the reply rendered as an outbound row. `tests/test-ids-parity.test.ts` asserts four Kotlin call sites instead of two Vue files by path (an ENOENT that would have fired the moment the PWA was deleted). All four specs share ONE fixture broker. |
| **5 — pipelines** | `8467711e`. `build-binary.sh` lost the 3-rung Vite ladder for a JDK precondition + `:web:stageForBroker`; the Dockerfile became multi-stage (`eclipse-temurin:17-jdk AS webbuild` → `oven/bun:1` runtime, no JDK in the runtime image); `ci.yml` dropped both `src/web-app` installs, `setup-node` and the `vue-tsc` typecheck, gained a `web` paths filter/output and a JDK+Gradle `ui` lane; `release.yml`'s `build-binaries` matrix gained JDK 17 + Gradle on all three legs; `feel-deploy.sh`'s change map and staging step moved to Gradle; logo assets, `README.md`, `SETUP.md` and `docs/docs/web-channel-setup.md` follow. |
| **6 — deletion + sweep** | This commit. `git rm -r src/web-app` — **485 files, 33,929 lines**. `tsconfig.json`'s `exclude: ["src/web-app"]` (and its six-line comment) went in the same commit, because deleting the exclude earlier would have broken `bun run typecheck`. **22 files swept**: every `src/web-app/...` citation in a Kotlin/Swift/JS/XML comment now reads "(retired Vue PWA; see git history before 2026-09-12)", and each one keeps the invariant it described rather than just losing the pointer — e.g. `apps/android/codemirror/cm6-entry.mjs` now states the font-zoom rule itself (10–24 px, 13 px default, ±1 px per keystroke, `Cmd/Ctrl 0` resets, pinch scales base × curDist/baseDist then clamps) instead of pointing at a deleted `editor-font-zoom.ts`. Root devDeps `@codemirror/lsp-client`, `@codemirror/state`, `@codemirror/view` pruned (zero consumers in `src tests scripts` now that `apps/android/codemirror` has its own manifest; `playwright` stays) and the tracked root `bun.lock` refreshed. |

**Two pipeline follow-ups from the Task 5 review, also in this commit.**
`release.yml`: every job that reaches `scripts/build-binary.sh` indirectly now sets up the same
toolchain the direct callers do — `update-flow` (→ `test-update-flow.sh`, which builds two real
binaries) gains `setup-java` 17 **and** `setup-gradle`; `build-desktop-linux`,
`build-desktop-windows` and `build-compose-desktop-macos` (→ `stage-desktop-binaries.sh`) already
had a JDK and now get `gradle/actions/setup-gradle@v4` so the wasm build is cached rather than
recompiled cold on every release. `scripts/build-binary.sh`'s JDK check no longer stops at
`command -v java`: it parses `java -version` (both the `"1.8.0_392"` and the `"17.0.20"` / `"21"`
shapes) and fails with a readable message unless the major is ≥ 17 — an old JDK used to sail past
the check and die inside Gradle with a class-file error.

### Verification (all on this branch, this host)

| check | result |
|---|---|
| `bun test` (root, `.mux/verify.sh`) | **3036 pass, 3 skip, 0 fail** — 3039 tests / 425 files, 128 s |
| `bun run typecheck` | clean (`tsc --noEmit`, exit 0) — with the `src/web-app` exclude removed |
| `bun run test:ui` (4 Playwright journeys) | **ALL UI JOURNEYS PASS** |
| `:shared:jvmTest` | **985 pass**, 1 skipped |
| `:ui:jvmTest` (xvfb) | **1614 pass** |
| `:desktop:test` (xvfb) | **297 pass** |
| `:web:wasmJsBrowserTest` (Chrome) | **65 pass** |
| `:web:stageForBroker` | BUILD SUCCESSFUL — 3 hashed assets, gzip total **6376 KB** (8 MiB guard fine) |
| `scripts/build-binary.sh` + `scripts/smoke-binary.sh` | built, **SMOKE PASS 3/3** (version, `/me` → 401, embedded client + auth) |
| `docker build .` | **GREEN**, unmodified — `webbuild` stage **1453 s**, image **3.57 GB**; `docker run --rm supermux-web-cutover ls src/channels/web/static` lists the full client (`index.html`, `assets/`, `editor/`, `icons/`, `sw.js`, `manifest.webmanifest`, `xterm.css`, `favicon.ico`) |

**Journey timings** (one shared fixture broker, headless Chrome): `core-journey` **10.2 s**,
`composer-attachment` **8.8 s**, `voice-recorder` **9.9 s**, `push-banner` **9.9 s**.

**Sizes.** Staged `src/channels/web/static` **23,078,821 B** across 28 files
(`supermux-apps-web-*.wasm` 11,223,305 · `skiko-*.wasm` 8,640,316 · `app-*.js` 993,828);
gzip total 6376 KB. Linux x64 binary **134,248,576 B** (the whole client is embedded, one
`with { type: "file" }` import per staged file). Docker image ****3.57 GB** (`webbuild` JDK stage discarded; the runtime image is `oven/bun:1` + the staged tree)**.

**`:web:stageForBroker` is the proof the deletion is complete.** It was run from a wiped
`src/channels/web/static` on a tree with no `src/web-app` at all and produced the full client,
editor bundle included — the editor's source of truth is `apps/android/src/main/assets/editor`,
copied by the Gradle task, not anything the Vue app ever owned.

**Interop hole: STILL PRESENT.** The 32 px transparent band above `HtmlElementView` panes survived
CMP 1.12.0 (re-measured during the toolchain bump: with a magenta body, every scanline from y=0 to
y=31 above a real mounted xterm is `rgb(255,0,255)`). Nothing in plan 5 addresses it; keep the
known-issue note in the spec.

**Still unverified on this host:** the Apple targets. `:ios` and the Apple source sets of
`:shared`/`:ui` are CONFIGURED but never compiled here (Kotlin/Native `ios*`/`uikit*` need a macOS
konan host), so the **first macOS build of this branch is a verification step** for Kotlin 2.4.10 /
SKIE 0.10.14 / coil 3.5.0. `scripts/test-android.sh` (the emulator journey) was likewise not
runnable — no device attached.

### Remaining `web-app` hits, and why each stays

- `apps/web/src/wasmJsMain/resources/index.html:19,22,25` — `apple-mobile-web-app-capable`,
  `mobile-web-app-capable`, `apple-mobile-web-app-status-bar-style`. Standard PWA meta tags; the
  substring is a coincidence.
- `apps/android/codemirror/README.md:4` — a deliberate history mention ("mirrors the retired Vue
  web editor's CodeMirror setup"), no path citation.
- `graphify-out/**` (10 files) — a committed, *generated* knowledge-graph snapshot (`manifest.json`,
  `graph.json`, `GRAPH_REPORT.md`, AST/semantic caches) that still indexes the old tree. It is
  regenerated wholesale by `/graphify`, not hand-edited; rewriting it here would be a meaningless
  ~850-file diff. Refresh it on the next graphify run.

`.gitignore` and `.dockerignore` needed no history comment: Task 5 had already rewritten both to
talk about `cd apps && ./gradlew :web:stageForBroker` and the `webbuild` stage.

### What changed for operators

**Building supermux now needs a JDK 17+ and Gradle.** The web client is Kotlin/Wasm Compose, and
the only thing that can produce it is `cd apps && ./gradlew :web:stageForBroker`; there is no Vite
fallback ladder to hide behind, so `scripts/build-binary.sh` fails fast (and now checks the JDK
*major*, not merely that `java` exists) rather than dying inside Gradle. Budget the memory: Kotlin
2.4's wasm backend needs `kotlin.daemon.jvmargs=-Xmx6g`, which `apps/gradle.properties` sets for
everyone — a machine or container with less than ~8 GB free will OOM or GC-thrash where it used to
merely be slow, and a cold `:web:stageForBroker` is roughly 14 minutes (seconds when warm). Docker
users are unaffected in the runtime image: the Dockerfile is multi-stage, a `eclipse-temurin:17-jdk`
`webbuild` stage compiles the client and the `oven/bun:1` runtime image only copies
`src/channels/web/static` — no JDK, no Node, no Vite in what you run. Android builders need one
extra step: compileSdk is 37 and Android SDK platforms are minor-versioned now, so install it from
the **beta** channel with `sdkmanager --channel=1 "platforms;android-37.0"` (plain
`platforms;android-37` does not exist). `bun run test:ui` stages the client through Gradle too —
export `MUX_TEST_SKIP_WEB_BUILD=1` when the staged tree is already current and you only want the
four browser journeys. Finally, iOS: the first macOS build of this branch is the verification step
for Kotlin 2.4.10 / SKIE 0.10.14, since no Apple target can be compiled on Linux.
