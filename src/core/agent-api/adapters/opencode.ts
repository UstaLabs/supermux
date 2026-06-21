// OpenCode adapter — Zen or Go variants. One-shot text completion against the
// OpenAI-compatible chat/completions backend, reusing the API key from
// ~/.local/share/opencode/auth.json (Zen → `opencode.key`, Go → `opencode-go.key`).
// API keys, no refresh. All I/O is injectable (fetchFn / readFileFn) so the adapter
// is fully unit-testable with no network or disk.

import { homedir } from "os"
import { join } from "path"
import { defaultRead, readJson } from "../auth"
import { DEFAULT_TIMEOUT_MS, type AgentApi, type CompleteOpts, type FetchFn, type ReadFileFn } from "../types"

export type OpencodeVariant = "zen" | "go"

interface VariantConfig {
  name: string
  url: string
  keyField: string // top-level key in auth.json holding `{ key }`
  defaultModel: string
}

const VARIANTS: Record<OpencodeVariant, VariantConfig> = {
  // Zen free models are promos; deepseek-v4-flash-free is a fast default.
  zen: {
    name: "opencode-zen",
    url: "https://opencode.ai/zen/v1/chat/completions",
    keyField: "opencode",
    defaultModel: "deepseek-v4-flash-free",
  },
  // Go is a flat-plan; pick a fast default model (configurable via opts.model).
  go: {
    name: "opencode-go",
    url: "https://api.opencode.ai/go/v1/chat/completions",
    keyField: "opencode-go",
    defaultModel: "grok-code-fast-1",
  },
}

export interface OpencodeAdapterOpts {
  fetchFn?: FetchFn
  readFileFn?: ReadFileFn
  authPath?: string
}

export function opencodeAdapter(variant: OpencodeVariant, opts: OpencodeAdapterOpts = {}): AgentApi {
  const cfg = VARIANTS[variant]
  const fetchFn = opts.fetchFn ?? fetch
  const read = opts.readFileFn ?? defaultRead
  const authPath = opts.authPath ?? join(homedir(), ".local", "share", "opencode", "auth.json")

  const loadKey = (): string | undefined => {
    const key = readJson(read, authPath)?.[cfg.keyField]?.key
    return typeof key === "string" && key ? key : undefined
  }

  return {
    name: cfg.name,

    isAvailable(): boolean {
      return Boolean(loadKey())
    },

    async complete(prompt: string, complOpts: CompleteOpts = {}): Promise<string> {
      const key = loadKey()
      if (!key) throw new Error(`${cfg.name}: no api key in auth.json`)

      const model = complOpts.model ?? cfg.defaultModel
      const body = JSON.stringify({ model, messages: [{ role: "user", content: prompt }] })

      const timeoutMs = complOpts.timeoutMs ?? DEFAULT_TIMEOUT_MS
      const timer = complOpts.signal ? undefined : AbortSignal.timeout(timeoutMs)
      const signal = complOpts.signal ?? timer

      const res = await fetchFn(cfg.url, {
        method: "POST",
        headers: { Authorization: `Bearer ${key}`, "Content-Type": "application/json" },
        body,
        signal,
      })

      if (!res.ok) {
        const detail = await res.text().catch(() => "")
        throw new Error(`${cfg.name}: chat/completions ${res.status}${detail ? ` ${detail.slice(0, 200)}` : ""}`)
      }

      const json: any = await res.json().catch(() => null)
      const text = String(json?.choices?.[0]?.message?.content ?? "").trim()
      if (!text) throw new Error(`${cfg.name}: empty completion`)
      return text
    },
  }
}
