// Codex adapter — DEFAULT engine. One-shot text completion against the ChatGPT
// subscription backend (officially sanctioned). Reuses the logged-in creds from
// ~/.codex/auth.json and calls the OpenAI Responses API rewritten to the codex
// path. All I/O is injectable (fetchFn / readFileFn / writeFileFn) so the adapter
// is fully unit-testable with no network or disk.

import { writeFileSync } from "fs"
import { homedir } from "os"
import { join } from "path"
import { defaultRead, jwtClaims, readJson, refreshOAuth } from "../auth"
import { DEFAULT_TIMEOUT_MS, type AgentApi, type CompleteOpts, type FetchFn, type ReadFileFn } from "../types"

const RESPONSES_URL = "https://chatgpt.com/backend-api/codex/responses"
const OAUTH_TOKEN_URL = "https://auth.openai.com/oauth/token"
// The official Codex CLI OAuth client id (subscription mode).
const OAUTH_CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"
// ChatGPT-account Codex rejects the `gpt-5-codex` slug; a small GPT is the fast default.
const DEFAULT_CODEX_MODEL = "gpt-5.4-mini"
// The Responses endpoint requires `instructions`; keep it minimal — the cleanup task
// itself lives in the prompt the orchestrator builds.
const DEFAULT_INSTRUCTIONS = "You are a helpful assistant."

type WriteFileFn = (path: string, data: string) => void

export interface CodexAdapterOpts {
  fetchFn?: FetchFn
  readFileFn?: ReadFileFn
  writeFileFn?: WriteFileFn
  authPath?: string
}

// Pull the chatgpt account id from the explicit field or the access-token JWT claim.
function accountId(tokens: any): string | undefined {
  if (tokens?.account_id) return String(tokens.account_id)
  const claims = jwtClaims(String(tokens?.access_token ?? ""))
  const id = claims?.["https://api.openai.com/auth"]?.chatgpt_account_id
  return id ? String(id) : undefined
}

// Extract assistant text from a non-streaming Responses API payload (JSON).
function parseJsonOutput(json: any): string {
  if (typeof json?.output_text === "string") return json.output_text
  const parts: string[] = []
  for (const item of json?.output ?? []) {
    for (const c of item?.content ?? []) {
      if (typeof c?.text === "string") parts.push(c.text)
    }
  }
  return parts.join("")
}

// The ChatGPT-account Codex endpoint forces `stream: true` and replies with SSE.
// Accumulate `response.output_text.delta` events; prefer the final `output_text.done`
// / `response.completed` text if present (handles obfuscated/duplicated deltas).
function parseSse(raw: string): string {
  let deltas = ""
  let done = ""
  for (const block of raw.split("\n\n")) {
    const dataLine = block.split("\n").find((l) => l.startsWith("data:"))
    if (!dataLine) continue
    const payload = dataLine.slice(5).trim()
    if (!payload || payload === "[DONE]") continue
    let evt: any
    try {
      evt = JSON.parse(payload)
    } catch {
      continue
    }
    if (evt?.type === "response.output_text.delta" && typeof evt.delta === "string") deltas += evt.delta
    else if (evt?.type === "response.output_text.done" && typeof evt.text === "string") done = evt.text
    else if (evt?.type === "response.completed") {
      const t = parseJsonOutput(evt.response)
      if (t) done = t
    }
  }
  return done || deltas
}

// Parse either an SSE stream or a plain-JSON Responses payload into assistant text.
async function parseResponse(res: Response): Promise<string> {
  const ct = res.headers.get("content-type") ?? ""
  const body = await res.text()
  if (ct.includes("text/event-stream") || /^event:|\ndata:|^data:/.test(body)) return parseSse(body)
  try {
    return parseJsonOutput(JSON.parse(body))
  } catch {
    // last resort: maybe it was SSE without the content-type hint
    return parseSse(body)
  }
}

export function codexAdapter(opts: CodexAdapterOpts = {}): AgentApi {
  const fetchFn = opts.fetchFn ?? fetch
  const read = opts.readFileFn ?? defaultRead
  const write: WriteFileFn = opts.writeFileFn ?? ((p, d) => writeFileSync(p, d, "utf8"))
  const authPath = opts.authPath ?? join(homedir(), ".codex", "auth.json")

  const loadTokens = (): any | null => readJson(read, authPath)?.tokens ?? null

  const buildBody = (prompt: string, model: string): string =>
    JSON.stringify({
      model,
      instructions: DEFAULT_INSTRUCTIONS,
      input: [{ role: "user", content: [{ type: "input_text", text: prompt }] }],
      store: false,
      stream: true,
    })

  const post = (token: string, acct: string | undefined, body: string, signal?: AbortSignal): Promise<Response> => {
    const headers: Record<string, string> = {
      Authorization: `Bearer ${token}`,
      originator: "codex_cli_rs",
      "OpenAI-Beta": "responses=experimental",
      "Content-Type": "application/json",
    }
    if (acct) headers["chatgpt-account-id"] = acct
    return fetchFn(RESPONSES_URL, { method: "POST", headers, body, signal })
  }

  return {
    name: "codex",

    isAvailable(): boolean {
      return Boolean(loadTokens()?.access_token)
    },

    async complete(prompt: string, complOpts: CompleteOpts = {}): Promise<string> {
      const auth = readJson(read, authPath)
      const tokens = auth?.tokens
      if (!tokens?.access_token) throw new Error("codex: no access_token in auth.json")

      const model = complOpts.model ?? DEFAULT_CODEX_MODEL
      const body = buildBody(prompt, model)

      const timeoutMs = complOpts.timeoutMs ?? DEFAULT_TIMEOUT_MS
      const timer = complOpts.signal ? undefined : AbortSignal.timeout(timeoutMs)
      const signal = complOpts.signal ?? timer

      let res = await post(tokens.access_token, accountId(tokens), body, signal)

      // On 401, attempt ONE refresh-token grant, write the new tokens back, retry once.
      if (res.status === 401 && tokens.refresh_token) {
        const refreshed = await refreshOAuth(fetchFn, OAUTH_TOKEN_URL, {
          grant_type: "refresh_token",
          client_id: OAUTH_CLIENT_ID,
          refresh_token: tokens.refresh_token,
        })
        const newAccess = refreshed.access_token
        const newRefresh = refreshed.refresh_token ?? tokens.refresh_token
        if (!newAccess) throw new Error("codex: refresh returned no access_token")
        const merged = {
          ...(auth ?? {}),
          tokens: { ...tokens, access_token: newAccess, refresh_token: newRefresh, id_token: refreshed.id_token ?? tokens.id_token },
          last_refresh: new Date().toISOString(),
        }
        try {
          write(authPath, JSON.stringify(merged, null, 2))
        } catch {
          // best-effort write-back; a failed write must not break the completion
        }
        res = await post(newAccess, accountId(merged.tokens), body, signal)
      }

      if (!res.ok) {
        const detail = await res.text().catch(() => "")
        throw new Error(`codex: responses ${res.status}${detail ? ` ${detail.slice(0, 200)}` : ""}`)
      }

      const text = (await parseResponse(res)).trim()
      if (!text) throw new Error("codex: empty completion")
      return text
    },
  }
}
