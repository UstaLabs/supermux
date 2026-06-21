# Direct-API Agent Adapter Layer — Design (2026-06-21)

## Goal

Replace the voice-cleanup engine plumbing with a clean **adapter layer** that calls each
agent's model backend via a **direct HTTP request**, reusing the agent's existing logged-in
**subscription credentials** (not a separately-billed API key, not the CLI subprocess). One
interface; each agent implements it. Default engine: **Codex (ChatGPT subscription — officially
sanctioned)**. Engine + model selectable via app-config / env (settings-page UI is a follow-up).

This is informed by verified deep research (2026-06-21, see `domains/claudemux.md` + workflow
`was5a3ip2`). Key facts: the four agents are NOT equivalent on ToS/ban risk — Codex (ChatGPT)
is officially sanctioned, Claude direct-token reuse VIOLATES Anthropic's ToS with demonstrated
bans, Cursor is reverse-engineered protobuf, OpenCode Zen is metered while Go is a $10/mo flat plan.

## Interface

`src/core/agent-api/types.ts`:

```ts
export interface AgentApi {
  readonly name: string                 // "codex" | "opencode-zen" | ...
  isAvailable(): boolean                 // creds on disk + (gated agents) opt-in flag set
  complete(prompt: string, opts?: CompleteOpts): Promise<string>  // one-shot; returns assistant text; THROWS on failure
}
export interface CompleteOpts { model?: string; timeoutMs?: number; signal?: AbortSignal }
```

- `complete` does ONE one-shot text completion and returns the assistant text. Throws on any
  failure (non-2xx, empty, timeout) so the orchestrator can fall back.
- `isAvailable` checks creds exist (+ opt-in for gated agents) WITHOUT making a network call.

## Module structure

`src/core/agent-api/`
- `types.ts` — `AgentApi` interface + `CompleteOpts`.
- `prompt.ts` — `buildCleanupPrompt(input)` (moved from voice-cleanup.ts; shared, pure, tested).
- `auth.ts` — shared helpers: read auth files, OAuth refresh-and-write-back (per-agent endpoints), JWT claim decode (no verify).
- `adapters/codex.ts`, `opencode.ts`, `claude.ts`, `cursor.ts`, `cursor-cli.ts`
- `index.ts` — registry `{ codex, "opencode-zen", "opencode-go", claude, cursor, "cursor-cli" }` + `select(engine): AgentApi`.

All HTTP via injectable `fetchFn` (default global `fetch`); all file reads via injectable
`readFileFn` — so every adapter is unit-testable with no network/disk.

## Adapters (verified specifics)

### codex — DEFAULT (ChatGPT subscription, sanctioned)
- Creds: `~/.codex/auth.json` → `tokens.access_token` (+ `refresh_token`, `account_id`); `OPENAI_API_KEY` null ⇒ subscription mode.
- Endpoint: `POST https://chatgpt.com/backend-api/codex/responses` (OpenAI Responses API; `/responses` rewritten to `/codex/responses`).
- Headers: `Authorization: Bearer <access_token>`, `chatgpt-account-id: <account_id>` (from JWT claim `https://api.openai.com/auth`), `originator: codex_cli_rs`, `OpenAI-Beta: responses=experimental`, `Content-Type: application/json`. NO `x-api-key`.
- Body: Responses API — `{ model, input: [{ role: "user", content: [{ type: "input_text", text: prompt }] }], store: false }`. (Implementer: verify the exact Responses shape against openai/codex; accept the simple `input: prompt` form if the structured one is rejected.)
- Response: concatenate output text items (`output[].content[].text`, type `output_text`); the implementer must handle SSE if the endpoint streams (request non-stream if possible).
- Refresh: on expiry, `POST https://auth.openai.com/oauth/token` (x-www-form-urlencoded) `grant_type=refresh_token`, `client_id=app_EMoamEEZ73f0CkXaXp7hrann`; write tokens + `last_refresh` back to auth.json.
- Model: fast GPT default (the Codex default model); configurable.

### opencode — Zen or Go (engine variants `opencode-zen`, `opencode-go`)
- Creds: `~/.local/share/opencode/auth.json` → `opencode.key` (Zen) / `opencode-go.key` (Go). API key, no refresh.
- Endpoint: Zen `POST https://opencode.ai/zen/v1/chat/completions`; Go `POST https://api.opencode.ai/go/v1/chat/completions`. OpenAI-compatible.
- Headers: `Authorization: Bearer <key>`, `Content-Type: application/json`.
- Body: `{ model, messages: [{ role: "user", content: prompt }] }`. Response: `choices[0].message.content`.
- Models: Zen free models are PROMOS (deepseek-v4-flash-free, north-mini-code-free; some promos already ended); Go = flat-plan models. Configurable per variant.

### claude — GATED (ban risk)
- Creds: `~/.claude/.credentials.json` → `claudeAiOauth.accessToken` (+ refreshToken, expiresAt).
- Endpoint: `POST https://api.anthropic.com/v1/messages`.
- Headers: `Authorization: Bearer <accessToken>`, `anthropic-beta: claude-code-20250219,oauth-2025-04-20` (REQUIRED — dropping → 401), `anthropic-version: 2023-06-01`, `Content-Type: application/json`. NO `x-api-key`.
- Body: `{ model, max_tokens, system: "You are Claude Code, Anthropic's official CLI for Claude.", messages: [{ role: "user", content: prompt }] }`. Response: `content[0].text`.
- GATE: `isAvailable()` returns false unless `MUX_VOICE_CLEANUP_ALLOW_CLAUDE=1`. On use, log a one-time ban-risk warning. Refresh URL is UNVERIFIED — on 401/expiry, FAIL (let the orchestrator fall back); do NOT guess a refresh endpoint.
- Model: `claude-haiku-4-5` (fast); configurable.

### cursor — protobuf/gRPC (HEAVY; implement last)
- Creds: `~/.config/cursor/auth.json` → `accessToken` (+ refreshToken).
- Endpoint: `https://api2.cursor.sh`, Connect-gRPC over HTTP/2, protobuf. Procedure `/aiserver.v1.AiService/StreamChat` (+ `/aiserver.v1.AiService/AvailableModels`). content-type `application/connect+proto` (streaming) / `application/proto` (unary).
- Headers: `authorization: Bearer <accessToken>`, `x-cursor-client-version`, `x-cursor-client-type: cli`, `x-ghost-mode: true`, `x-cursor-checksum: <any>`.
- Approach: vendor the minimal `.proto` (from everestmz/cursor-rpc) + use a protobuf/Connect client (or port ephraimduncan/opencode-cursor's h2-bridge that exposes a local OpenAI-compatible shim). Implement a minimal one-shot completion via StreamChat; collect the streamed text. Refresh varies (`/oauth/token` grant_type=refresh_token OR `/auth/exchange_user_api_key`) — handle fail-soft.
- Model: discover via AvailableModels at runtime; pick a fast one; configurable.

### cursor-cli — universal fallback
- The current `cursor-agent -p --output-format text --model composer-2.5-fast --force` one-shot (already in voice-cleanup.ts). Reliable under the broker. Always the last fallback.

## Selection / config

- app-config: `voiceCleanupEngine` (`codex` | `opencode-zen` | `opencode-go` | `claude` | `cursor` | `cursor-cli`), `voiceCleanupModel`. Default `codex`.
- Env overrides: `MUX_VOICE_CLEANUP_ENGINE`, `MUX_VOICE_CLEANUP_MODEL`, `MUX_VOICE_CLEANUP_ALLOW_CLAUDE`.
- Settings-page dropdown is a FOLLOW-UP; config/env is the source of truth now.

## Orchestration (voice-cleanup.ts → uses agent-api)

`cleanupDraft(input)`:
1. If draft empty → `{text:"", engine:"none"}`.
2. `const a = select(cfg.engine)`; if `a.isAvailable()` → `try a.complete(prompt)` → `{text, engine: a.name}`.
3. On throw/empty OR not-available → fall back to `cursor-cli` adapter.
4. If the fallback also fails → throw (caller keeps the raw draft, as today).
Returns `{ text, engine }`. The transcribe closure logs `engine` in `voice_transcribe_out`.

## Testing

- Each adapter: unit test with injected `fetchFn` + `readFileFn` — assert endpoint URL, auth headers, request body shape, response parsing, `isAvailable()` gating (esp. Claude opt-in), and refresh-trigger logic (codex).
- `buildCleanupPrompt`: shared prompt test.
- `cleanupDraft`/`select`: selection + fallback-chain tests (primary fails → cursor-cli).
- Full suite must stay green.

## Out of scope (now)
- Settings-page UI dropdown (config/env is the source of truth).
- Rate-limit handling beyond timeouts + fail-soft.
- Using these adapters for tasks other than voice cleanup (the interface is general, but only voice wires it now).
