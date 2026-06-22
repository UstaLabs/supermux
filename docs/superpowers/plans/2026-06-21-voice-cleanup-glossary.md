# Voice-cleanup Glossary — Implementation Plan

> Subagent-driven (TDD + spec/quality review per task). Two phases: broker (deploys, no reinstall) then iOS (build 19 + OTA).

**Goal:** A user-editable glossary of project/technical terms that (1) is injected into the codex cleanup prompt so it stops mis-correcting them (e.g. "Supermux" not "Supermaven"), and (2) is fed to the iOS on-device recognizer as contextual hints so it gets them right at the source.

**Why:** Logged proof — on-device heard "super max / collots / Kodaks"; codex "cleaned" to "Supermaven / Kolt / Codex". codex's world priors beat the buried conversation context. An explicit glossary fixes both layers.

---

## PHASE 1 — Broker (config + prompt + API)

### Task 1: Glossary config + prompt injection
**Files:** `src/core/settings/app-config.ts`, `src/core/agent-api/prompt.ts`, `src/main.ts` (transcribe closure), `tests/agent-api/prompt.test.ts`, `tests/voice-app-config.test.ts`
- [ ] app-config: add `voiceCleanupGlossary?: string[]` (mirror the existing `voiceCleanupModel` plumbing: parse/validate as a string array; default seed `["Supermux","Claude","Codex","Whisper","Haiku","Opus","Sonnet","OpenCode","Cursor","Tailscale"]`). Tolerate a comma-separated string too (coerce → array).
- [ ] `CleanupInput` (prompt.ts): add `glossary: string[]`. `buildCleanupPrompt` adds, when non-empty:
  `\nKnown terms (use these EXACT spellings if a draft word sounds similar, even if a different similarly-named product exists): <glossary joined ", ">`
  and tighten line 15 to reference "the glossary, conversation context, and command/skill names".
- [ ] transcribe closure (main.ts): pass `glossary: cfg.voiceCleanupGlossary ?? []` into `cleanupDraft`'s input (thread `glossary` through `CleanupOpts`/`cleanupDraft` → `buildCleanupPrompt`).
- [ ] Tests: prompt includes the glossary line + a sample term; app-config parses/defaults the glossary.
- [ ] Commit.

### Task 2: Glossary config API (for the app to read/edit)
**Files:** `src/channels/web/index.ts` (routes), test
- [ ] Find the existing app-config read/write path (settings.getAppConfig / setAppConfig). Add auth-gated routes: `GET /config/voice-glossary` → `{ glossary: string[] }`; `PUT /config/voice-glossary` (body `{ glossary: string[] }`) → persists via the settings store. Mirror an existing route's auth + error handling.
- [ ] Test the route (get returns the default/stored list; put updates it).
- [ ] Commit.

→ Parent deploys Phase 1 (FF merge + restart). codex cleanup now uses the glossary.

---

## PHASE 2 — iOS (build 19 + OTA)

### Task 3: BrokerSession glossary API + Settings screen
**Files:** `apps/iosApp/Supermux/Broker/BrokerSession.swift`, new `apps/iosApp/Supermux/.../GlossaryView.swift`, a settings entry, `project.yml` (build 19)
- [ ] BrokerSession: `fetchGlossary() async throws -> [String]` (GET) + `updateGlossary(_ terms: [String]) async throws` (PUT), Bearer auth (mirror transcribeDraft).
- [ ] `GlossaryView`: a List of terms with swipe-to-delete + a text field to add (trims, dedupes), persisting via `updateGlossary`. Loads via `fetchGlossary` on appear.
- [ ] Wire an entry to it (a nav-menu / settings item, e.g. near the STT debug button or a Settings sheet).
- [ ] project.yml build 18 → 19.

### Task 4: On-device contextual hints
**Files:** `apps/iosApp/Supermux/Chat/SpeechDictation.swift`, `ChatView.swift`
- [ ] `SpeechDictation.start(contextualStrings: [String] = [])` — pass into the SpeechAnalyzer/SpeechTranscriber (iOS 26 contextual strings / custom vocab if available) AND the legacy `SFSpeechAudioBufferRecognitionRequest.contextualStrings`. (Research the exact SpeechAnalyzer API for contextual hints; if SpeechAnalyzer lacks it, apply to the legacy path + note.)
- [ ] ChatView: fetch the glossary (cache from the broker) and pass it to `dictation.start(contextualStrings:)`.
- [ ] Build 19 + OTA refresh (same Tailscale link), verify served version 19.

---

## Notes
- Glossary lives in the broker (shared across devices; the cleanup runs broker-side).
- Phase 1 helps codex immediately; Phase 2 fixes on-device at the source + gives the management UI.
- Keep the full `bun test` suite green; don't break the agent-api adapter layer.
