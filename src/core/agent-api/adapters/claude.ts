// Claude adapter — GATED (ban risk). One-shot text completion against the Anthropic
// Messages API, reusing the Claude Code OAuth access token from
// ~/.claude/.credentials.json (`claudeAiOauth.accessToken`).
//
// WARNING: Reusing the Claude Code subscription token for direct API calls VIOLATES
// Anthropic's ToS and has caused account bans. This adapter is OFF unless the user
// explicitly opts in via MUX_VOICE_CLEANUP_ALLOW_CLAUDE=1. We never guess a refresh
// endpoint; on 401/expiry we THROW so the orchestrator can fall back.
//
// All I/O is injectable (fetchFn / readFileFn) so the adapter is fully unit-testable
// with no network or disk.

import { homedir } from "os"
import { join } from "path"
import { makeLogger } from "../../../shared/log"
import { defaultRead, readJson } from "../auth"
import { DEFAULT_TIMEOUT_MS, type AgentApi, type CompleteOpts, type FetchFn, type ReadFileFn } from "../types"

const log = makeLogger("agent-api:claude")

const ENDPOINT = "https://api.anthropic.com/v1/messages"
const DEFAULT_MODEL = "claude-haiku-4-5"
const DEFAULT_MAX_TOKENS = 1024
const SYSTEM_PROMPT = "You are Claude Code, Anthropic's official CLI for Claude."

// Opt-in gate: the user must explicitly accept the ban risk.
const gateOpen = (): boolean => process.env.MUX_VOICE_CLEANUP_ALLOW_CLAUDE === "1"

export interface ClaudeAdapterOpts {
  fetchFn?: FetchFn
  readFileFn?: ReadFileFn
  credsPath?: string
}

let warned = false

export function claudeAdapter(opts: ClaudeAdapterOpts = {}): AgentApi {
  const fetchFn = opts.fetchFn ?? fetch
  const read = opts.readFileFn ?? defaultRead
  const credsPath = opts.credsPath ?? join(homedir(), ".claude", ".credentials.json")

  const loadToken = (): string | undefined => {
    const tok = readJson(read, credsPath)?.claudeAiOauth?.accessToken
    return typeof tok === "string" && tok ? tok : undefined
  }

  return {
    name: "claude",

    // FALSE unless opted in AND creds present — never a network call.
    isAvailable(): boolean {
      return gateOpen() && Boolean(loadToken())
    },

    async complete(prompt: string, complOpts: CompleteOpts = {}): Promise<string> {
      if (!gateOpen()) {
        throw new Error("claude: disabled (set MUX_VOICE_CLEANUP_ALLOW_CLAUDE=1 to opt in; ToS/ban risk)")
      }

      const token = loadToken()
      if (!token) throw new Error("claude: no accessToken in .credentials.json")

      // One-time ban-risk warning when the gated path is actually exercised.
      if (!warned) {
        warned = true
        log.warn("claude_cleanup_ban_risk", {
          msg: "Using the Claude Code subscription token for direct API calls violates Anthropic's ToS and may get the account banned.",
        })
      }

      const model = complOpts.model ?? DEFAULT_MODEL
      const body = JSON.stringify({
        model,
        max_tokens: DEFAULT_MAX_TOKENS,
        system: SYSTEM_PROMPT,
        messages: [{ role: "user", content: prompt }],
      })

      const timeoutMs = complOpts.timeoutMs ?? DEFAULT_TIMEOUT_MS
      const timer = complOpts.signal ? undefined : AbortSignal.timeout(timeoutMs)
      const signal = complOpts.signal ?? timer

      const res = await fetchFn(ENDPOINT, {
        method: "POST",
        headers: {
          Authorization: `Bearer ${token}`,
          "anthropic-beta": "claude-code-20250219,oauth-2025-04-20",
          "anthropic-version": "2023-06-01",
          "Content-Type": "application/json",
        },
        body,
        signal,
      })

      // On 401/expiry: do NOT guess a refresh endpoint — fail so the orchestrator falls back.
      if (!res.ok) {
        const detail = await res.text().catch(() => "")
        throw new Error(`claude: messages ${res.status}${detail ? ` ${detail.slice(0, 200)}` : ""}`)
      }

      const json: any = await res.json().catch(() => null)
      const text = String(json?.content?.[0]?.text ?? "").trim()
      if (!text) throw new Error("claude: empty completion")
      return text
    },
  }
}
