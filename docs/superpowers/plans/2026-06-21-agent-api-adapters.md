# Direct-API Agent Adapter Layer — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax.

**Goal:** A `src/core/agent-api/` adapter layer that does one-shot text completion against each agent's model backend via direct HTTP using its existing subscription creds; voice cleanup uses it (default Codex), with a cursor-CLI fallback.

**Architecture:** One interface `AgentApi { name; isAvailable(); complete(prompt, opts) }`. Per-agent adapters in `adapters/`. A registry `select(engine)`. `voice-cleanup.ts` builds the prompt, selects the configured adapter, falls back to cursor-cli. All HTTP via injectable `fetchFn`, all file reads via injectable `readFileFn` → fully unit-testable.

**Tech Stack:** Bun + TypeScript. `fetch` for HTTP. bun:test. Existing patterns: `src/core/transcription/voice-cleanup.ts`, `src/core/settings/app-config.ts`.

**Reference:** `docs/superpowers/specs/2026-06-21-agent-api-adapters-design.md` (full API specifics per agent).

**Order:** Tasks 1→6 deliver a working feature (Codex default + cursor-cli fallback + OpenCode/Claude options). Task 7 (Cursor protobuf) is the heavy final add-on.

---

### Task 1: Core — interface, prompt, auth helpers

**Files:**
- Create: `src/core/agent-api/types.ts`, `src/core/agent-api/prompt.ts`, `src/core/agent-api/auth.ts`
- Test: `tests/agent-api/prompt.test.ts`, `tests/agent-api/auth.test.ts`

- [ ] **Step 1 — types.ts:**
```ts
export interface CompleteOpts { model?: string; timeoutMs?: number; signal?: AbortSignal }
export interface AgentApi {
  readonly name: string
  isAvailable(): boolean
  complete(prompt: string, opts?: CompleteOpts): Promise<string>
}
export type FetchFn = typeof fetch
export type ReadFileFn = (path: string) => string   // throws if missing
export const DEFAULT_TIMEOUT_MS = Number(process.env.MUX_VOICE_CLEANUP_TIMEOUT_MS ?? 30_000)
```

- [ ] **Step 2 — prompt.ts:** move `buildCleanupPrompt` verbatim out of `voice-cleanup.ts` (the existing function: instructions + skills + recent messages + `Draft: <json>` + "Corrected text:"). Export `CleanupInput { draft; recentMessages: {role;text}[]; skills: string[] }` + `buildCleanupPrompt(input): string`.

- [ ] **Step 3 — prompt.test.ts:** assert it contains the draft, a context line, a skill name, and "Output ONLY the corrected text". Run `bun test tests/agent-api/prompt.test.ts` → PASS.

- [ ] **Step 4 — auth.ts helpers:**
```ts
import { readFileSync } from "fs"
export const readJson = (read: ReadFileFn, path: string): any | null => { try { return JSON.parse(read(path)) } catch { return null } }
export const defaultRead: ReadFileFn = (p) => readFileSync(p, "utf8")
// Decode a JWT payload WITHOUT verifying (for claims like chatgpt-account-id).
export function jwtClaims(token: string): any | null {
  const part = token.split(".")[1]; if (!part) return null
  try { return JSON.parse(Buffer.from(part.replace(/-/g,"+").replace(/_/g,"/"), "base64").toString("utf8")) } catch { return null }
}
// OAuth refresh-token grant (x-www-form-urlencoded) → JSON token response.
export async function refreshOAuth(f: FetchFn, url: string, body: Record<string,string>): Promise<any> {
  const res = await f(url, { method:"POST", headers:{ "Content-Type":"application/x-www-form-urlencoded" }, body: new URLSearchParams(body).toString() })
  if (!res.ok) throw new Error(`oauth refresh ${res.status}`)
  return res.json()
}
```

- [ ] **Step 5 — auth.test.ts:** `jwtClaims` decodes a hand-built `header.payloadB64.sig` (payload `{"https://api.openai.com/auth":{"chatgpt_account_id":"acc_1"}}`); `readJson` returns null on bad path (injected read that throws). Run → PASS.

- [ ] **Step 6 — Commit:** `git add src/core/agent-api/types.ts src/core/agent-api/prompt.ts src/core/agent-api/auth.ts tests/agent-api/ && git commit -m "feat(agent-api): core interface + prompt + auth helpers"`

---

### Task 2: Codex adapter (DEFAULT)

**Files:** Create `src/core/agent-api/adapters/codex.ts`; Test `tests/agent-api/codex.test.ts`

- [ ] **Step 1 — failing test (injected fetch + read):** assert `complete()` POSTs to `https://chatgpt.com/backend-api/codex/responses`, sends `Authorization: Bearer <access_token>`, `chatgpt-account-id` (from the token's JWT claim or `tokens.account_id`), `originator: codex_cli_rs`, `OpenAI-Beta: responses=experimental`, NO `x-api-key`, and parses the assistant text out of a Responses-API response. `isAvailable()` is true when `~/.codex/auth.json` has `tokens.access_token`, false when the file/field is missing.
- [ ] **Step 2 — run → FAIL.**
- [ ] **Step 3 — implement `codexAdapter({ fetchFn?, readFileFn?, authPath? })`:**
  - `isAvailable()`: `readJson(read, ~/.codex/auth.json)?.tokens?.access_token` truthy.
  - `complete(prompt, opts)`: read token + account_id (from `tokens.account_id` or `jwtClaims(access_token)["https://api.openai.com/auth"].chatgpt_account_id`); POST the Responses body `{ model: opts.model ?? DEFAULT_CODEX_MODEL, input: [{role:"user", content:[{type:"input_text", text:prompt}]}], store:false, stream:false }`; on 401 attempt ONE refresh via `refreshOAuth(f, "https://auth.openai.com/oauth/token", { grant_type:"refresh_token", client_id:"app_EMoamEEZ73f0CkXaXp7hrann", refresh_token })`, write tokens back to auth.json, retry once; parse `output_text` / `output[].content[].text`; trim; throw on empty.
  - Implementer: verify the live Responses shape; if the structured `input` is rejected, fall back to `input: prompt`. Handle a possibly-SSE response (request `stream:false`; if streaming is forced, accumulate `response.output_text.delta`).
- [ ] **Step 4 — run → PASS.**
- [ ] **Step 5 — live smoke (non-test):** `bun -e` calling `codexAdapter().complete("Correct and return ONLY: helo wrld")` → prints a sane correction in a few seconds. Note timing.
- [ ] **Step 6 — Commit:** `feat(agent-api): codex adapter (ChatGPT subscription, default)`

---

### Task 3: OpenCode adapter (Zen + Go)

**Files:** Create `src/core/agent-api/adapters/opencode.ts`; Test `tests/agent-api/opencode.test.ts`

- [ ] **Step 1 — failing test:** `opencodeAdapter("zen")` POSTs `https://opencode.ai/zen/v1/chat/completions`; `opencodeAdapter("go")` POSTs `https://api.opencode.ai/go/v1/chat/completions`; both send `Authorization: Bearer <key>` (zen→`opencode.key`, go→`opencode-go.key` from `~/.local/share/opencode/auth.json`), OpenAI body `{model, messages:[{role:"user",content:prompt}]}`, parse `choices[0].message.content`. `isAvailable()` reflects the key's presence.
- [ ] **Step 2 — run → FAIL.**
- [ ] **Step 3 — implement** the OpenAI-compatible POST + parse (one factory parameterized by base URL + key field). DEFAULT models: zen `deepseek-v4-flash-free`, go a fast default (configurable via opts.model).
- [ ] **Step 4 — run → PASS.**
- [ ] **Step 5 — live smoke** against zen (free model) → sane correction. (Skip go if no balance; note it.)
- [ ] **Step 6 — Commit:** `feat(agent-api): opencode adapter (zen + go)`

---

### Task 4: Claude adapter (GATED)

**Files:** Create `src/core/agent-api/adapters/claude.ts`; Test `tests/agent-api/claude.test.ts`

- [ ] **Step 1 — failing test:** `isAvailable()` is FALSE unless `MUX_VOICE_CLEANUP_ALLOW_CLAUDE=1` (even with creds present); with the flag + creds, `complete()` POSTs `https://api.anthropic.com/v1/messages` with `Authorization: Bearer <accessToken>`, `anthropic-beta: claude-code-20250219,oauth-2025-04-20`, `anthropic-version: 2023-06-01`, NO `x-api-key`, body `{model, max_tokens, system:"You are Claude Code, Anthropic's official CLI for Claude.", messages:[{role:"user",content:prompt}]}`, parses `content[0].text`.
- [ ] **Step 2 — run → FAIL.**
- [ ] **Step 3 — implement** reading `~/.claude/.credentials.json` → `claudeAiOauth.accessToken`; the gate; a one-time `log.warn("claude_cleanup_ban_risk", …)`; DEFAULT model `claude-haiku-4-5`, max_tokens ~1024. On 401/expiry → THROW (no refresh guess).
- [ ] **Step 4 — run → PASS.**
- [ ] **Step 5 — Commit:** `feat(agent-api): claude adapter (gated, ban-risk)` (no live smoke — don't exercise the ban-risk path by default).

---

### Task 5: cursor-cli adapter (fallback)

**Files:** Create `src/core/agent-api/adapters/cursor-cli.ts`; Test `tests/agent-api/cursor-cli.test.ts`

- [ ] **Step 1 — failing test:** `complete()` (with injected `run`) builds argv `["cursor-agent","-p",prompt,"--output-format","text","--model",model,"--force"]`, returns trimmed stdout, throws on non-zero/empty. `isAvailable()` true (cursor-agent assumed present) — or checks the binary.
- [ ] **Step 2 — run → FAIL.**
- [ ] **Step 3 — implement** by porting the existing `cleanupViaCli`/`spawnOneShot` logic from `voice-cleanup.ts` into this adapter (DEFAULT model `composer-2.5-fast`).
- [ ] **Step 4 — run → PASS.**
- [ ] **Step 6 — Commit:** `feat(agent-api): cursor-cli adapter (fallback)`

---

### Task 6: Registry + orchestration + config (feature goes live)

**Files:**
- Create: `src/core/agent-api/index.ts`; Test `tests/agent-api/index.test.ts`
- Modify: `src/core/transcription/voice-cleanup.ts` (use agent-api), `src/core/settings/app-config.ts` (add `voiceCleanupEngine`), `src/main.ts` (closure logs `out.engine`), `tests/voice-cleanup.test.ts` (update)

- [ ] **Step 1 — index.ts:** `registry: Record<Engine, () => AgentApi>` for `codex | opencode-zen | opencode-go | claude | cursor | cursor-cli`; `select(engine): AgentApi`; `VOICE_CLEANUP_ENGINE` (env `MUX_VOICE_CLEANUP_ENGINE` || config || `"codex"`), `VOICE_CLEANUP_MODEL`. (Cursor maps to cursor-cli until Task 7 lands; add a TODO.)
- [ ] **Step 2 — test select():** each engine string → the right adapter `.name`; unknown → throws or defaults to codex.
- [ ] **Step 3 — rewrite `cleanupDraft`** to: empty→`{text:"",engine:"none"}`; `const a=select(engine)`; if `a.isAvailable()` try `a.complete(buildCleanupPrompt(input), {model})` → `{text, engine:a.name}`; on throw/empty OR unavailable → `select("cursor-cli").complete(...)` → `{text, engine:"cursor-cli"}`; if that throws, rethrow. Keep the `{text, engine}` return.
- [ ] **Step 4 — update `tests/voice-cleanup.test.ts`** for the new selection + fallback (inject fetch/run via the adapters or via a select() seam). Keep prompt + empty-draft + fallback-chain coverage.
- [ ] **Step 5 — app-config:** add optional `voiceCleanupEngine?: string` (mirror `voiceCleanupModel` plumbing in `app-config.ts`).
- [ ] **Step 6 — main.ts:** the transcribe closure already logs `engine: out.engine` — confirm it compiles with the new return.
- [ ] **Step 7 — run full suite** `bun test` → green. Typecheck `bun run typecheck` → 0 errors.
- [ ] **Step 8 — Commit:** `feat(agent-api): registry + voice-cleanup orchestration + config (codex default, cursor-cli fallback)`

---

### Task 7: Cursor adapter (protobuf/gRPC — HEAVY)

**Files:** Create `src/core/agent-api/adapters/cursor.ts` (+ vendored `.proto` or a minimal protobuf encoder); Test `tests/agent-api/cursor.test.ts`

- [ ] **Step 1 — research/vendor:** pull the minimal `aiserver.v1` proto shapes from everestmz/cursor-rpc (StreamChat request/response + AvailableModels). Decide: a protobuf lib vs a hand-rolled minimal encoder for the few fields needed. Document the choice in a comment.
- [ ] **Step 2 — failing test:** `complete()` (injected fetch) POSTs `https://api2.cursor.sh/aiserver.v1.AiService/StreamChat` with `authorization: Bearer <accessToken>`, `x-cursor-client-type: cli`, `x-ghost-mode: true`, `x-cursor-checksum`, content-type `application/connect+proto`; the request body is the encoded protobuf; the streamed protobuf frames are decoded + concatenated to text. `isAvailable()` reflects `~/.config/cursor/auth.json` `accessToken`.
- [ ] **Step 3 — run → FAIL.**
- [ ] **Step 4 — implement** the encode/POST/stream-decode; model via AvailableModels or a configured default; on 401 fail-soft (refresh endpoint varies — try `/oauth/token` grant_type=refresh_token, else throw).
- [ ] **Step 5 — run → PASS.**
- [ ] **Step 6 — live smoke** against api2.cursor.sh → sane correction (or document the exact blocker if the protocol drifted).
- [ ] **Step 7 — wire** cursor engine in `index.ts` to this adapter (was cursor-cli placeholder). Run full suite → green.
- [ ] **Step 8 — Commit:** `feat(agent-api): cursor protobuf adapter`

---

## Self-review
- **Spec coverage:** interface (T1) ✓; codex (T2) ✓; opencode zen+go (T3) ✓; claude gated (T4) ✓; cursor-cli fallback (T5) ✓; selection/config/orchestration (T6) ✓; cursor protobuf (T7) ✓. All spec sections mapped.
- **Placeholders:** adapter bodies give real endpoints/headers/parsing; "implementer: verify" notes are intentional (live-API shape uncertainty), not placeholders.
- **Type consistency:** `AgentApi`/`CompleteOpts`/`CleanupInput`/`{text,engine}` used consistently across tasks; `select(engine)` + `buildCleanupPrompt` names stable.
- **Known risk:** T2 Codex Responses body shape + T7 Cursor protocol are live-verified during their tasks (smoke steps); both fail-soft to cursor-cli.
