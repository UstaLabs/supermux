import { createOpencodeClient } from "@opencode-ai/sdk"
import { spawnOpenCodeServer, type OpenCodeSpawnHandle } from "./spawn"
import { makeLogger } from "../../../shared/log"
import { join } from "path"
import { mkdirSync, existsSync, readFileSync } from "fs"
import { STATE_DIR } from "../../../shared/paths"
import { home } from "../../../shared/home"
import { openCodeDataDir } from "./auth"

const log = makeLogger("agents/opencode/auth-ops")

type RealClient = ReturnType<typeof createOpencodeClient>

// opencode auth is GLOBAL (one native data-dir auth.json shared by every
// session: XDG data on POSIX, LOCALAPPDATA on Windows). So auth management runs
// against a single lazily-started "control"
// server rather than any per-session server. First boot is ~20s; it's cached
// after that and torn down implicitly if the process exits.
let serverHandle: OpenCodeSpawnHandle | null = null
let client: RealClient | null = null
let bootPromise: Promise<RealClient> | null = null

async function getClient(): Promise<RealClient> {
  if (client) return client
  if (!bootPromise) {
    bootPromise = (async () => {
      const dir = join(STATE_DIR, "agents", "opencode", "_control")
      mkdirSync(dir, { recursive: true, mode: 0o700 })
      // Neutral config home (no mux-shim MCP — this server only does auth). We do
      // NOT override XDG_DATA_HOME, so it reads/writes the user's real auth.json.
      const handle = await spawnOpenCodeServer({ workdir: dir, configHome: join(dir, "config"), authEnv: {} })
      serverHandle = handle
      handle.onExit(() => { client = null; serverHandle = null; bootPromise = null })
      const c = createOpencodeClient({ baseUrl: handle.baseUrl } as never)
      client = c
      log.info("opencode_control_server_ready", { baseUrl: handle.baseUrl, pid: handle.pid })
      return c
    })().catch((err) => { bootPromise = null; throw err })
  }
  return bootPromise
}

export type OpenCodeAuthMethod = { type: "oauth" | "api" | string; label: string; index: number }
export type OpenCodeProviderInfo = { id: string; configured: boolean; methods: OpenCodeAuthMethod[] }

/** The user's real auth.json (provider-id → credential), used to mark which
 * providers are already connected. Read directly (cheap, no server needed). */
function readAuthJson(): Record<string, unknown> {
  const p = join(openCodeDataDir({ home: home() }), "auth.json")
  try {
    return existsSync(p) ? (JSON.parse(readFileSync(p, "utf8")) as Record<string, unknown>) : {}
  } catch {
    return {}
  }
}

function unwrap<T>(res: unknown): T {
  const r = res as { data?: T; error?: unknown }
  if (r && r.error) throw new Error(typeof r.error === "string" ? r.error : JSON.stringify(r.error))
  return r?.data as T
}

/** All providers opencode knows how to authenticate, each with its available
 * methods (oauth / api), and whether the user has already connected it. */
export async function listOpenCodeProviders(): Promise<OpenCodeProviderInfo[]> {
  const c = await getClient()
  const authed = readAuthJson()
  const methodsByProvider = unwrap<Record<string, Array<{ type: string; label: string }>>>(await c.provider.auth())
  const out: OpenCodeProviderInfo[] = []
  for (const [id, methods] of Object.entries(methodsByProvider ?? {})) {
    out.push({
      id,
      configured: id in authed,
      methods: (methods ?? []).map((m, i) => ({ type: m.type, label: m.label, index: i })),
    })
  }
  return out.sort((a, b) => a.id.localeCompare(b.id))
}

/** Save an API-key credential for a provider (Zen = providerId "opencode"). */
export async function setOpenCodeApiKey(providerId: string, key: string, metadata?: Record<string, string>): Promise<void> {
  const c = await getClient()
  const body: Record<string, unknown> = { type: "api", key }
  if (metadata && Object.keys(metadata).length) body.metadata = metadata
  unwrap(await c.auth.set({ path: { id: providerId }, body } as never))
}

/** Begin an OAuth/browser login for a provider+method; returns the authorization
 * URL the user opens in a browser. */
export async function startOpenCodeOAuth(
  providerId: string,
  method: number,
): Promise<{ url: string; instructions?: string; method: "auto" | "code" }> {
  const c = await getClient()
  const data = unwrap<{ url?: string; instructions?: string; method?: "auto" | "code" }>(
    await c.provider.oauth.authorize({ path: { id: providerId }, body: { method } } as never),
  )
  if (!data?.url) throw new Error("opencode oauth authorize returned no url")
  return { url: data.url, instructions: data.instructions, method: data.method ?? "code" }
}

/** Complete an OAuth login with the code the user pasted back from the browser. */
export async function finishOpenCodeOAuth(providerId: string, method: number, code: string): Promise<void> {
  const c = await getClient()
  unwrap(await c.provider.oauth.callback({ path: { id: providerId }, body: { method, code } } as never))
}
