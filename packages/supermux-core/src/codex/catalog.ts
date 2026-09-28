import { execFile } from 'node:child_process'
import { chmodSync, renameSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'

/**
 * Pin Codex subagents to multi_agent v1.
 *
 * Codex picks the multi-agent protocol per MODEL: the model catalog carries
 * `multi_agent_version` ("v1" | "v2"), and it wins over `features.multi_agent_v2`
 * (`--disable multi_agent_v2`, `-c features.multi_agent_v2=false` and a
 * thread/start `config` override were all verified to be ignored on
 * gpt-6-astra, the default model). Only v1 children accept direct client input
 * (`turn/start` / `turn/steer` on the child thread); v2 children answer -32600
 * "direct app-server input is not allowed for multi-agent v2 sub-agents".
 *
 * The catalog is loaded once at app-server start (a hot `config/batchWrite` of
 * `model_catalog_json` does NOT rebuild it), so the override is a process flag:
 * dump the live catalog (`codex debug models`), rewrite every "v2" to "v1",
 * write it into the session-private CODEX_HOME and start the app-server with
 * `-c model_catalog_json=<file>`. Any failure leaves the args unchanged; the
 * driver still handles v2 children (messaging "none").
 */
export const CATALOG_FILE = 'supermux-model-catalog.json'

export function pinCatalogToV1(raw: string): string | undefined {
  let catalog: any
  try { catalog = JSON.parse(raw) } catch { return undefined }
  const models = catalog?.models
  if (!Array.isArray(models) || !models.length) return undefined
  let changed = false
  for (const model of models) {
    if (model && typeof model === 'object' && model.multi_agent_version === 'v2') { model.multi_agent_version = 'v1'; changed = true }
  }
  return changed ? JSON.stringify(catalog) : undefined
}

function dumpCatalog(command: string, env: Record<string, string | undefined>, cwd: string, timeoutMs: number): Promise<string | undefined> {
  return new Promise(resolve => {
    try {
      execFile(command, ['debug', 'models'], { env: env as NodeJS.ProcessEnv, cwd, timeout: timeoutMs, maxBuffer: 64 * 1024 * 1024, encoding: 'utf8' }, (error, stdout) => resolve(error ? undefined : stdout))
    } catch { resolve(undefined) }
  })
}

/** Returns the app-server args with the v1 catalog override, or `args` unchanged. */
export async function multiAgentV1Args(command: string, args: string[], env: Record<string, string | undefined>, cwd: string, timeoutMs = 15_000): Promise<string[]> {
  // Only the real CLI shape (`codex app-server …`) with a session-private home.
  if (args[0] !== 'app-server') return args
  const home = env.CODEX_HOME
  if (!home) return args
  if (args.some(arg => arg.includes('model_catalog_json'))) return args
  const raw = await dumpCatalog(command, env, cwd, timeoutMs)
  const pinned = raw && pinCatalogToV1(raw)
  if (!pinned) return args
  const path = join(home, CATALOG_FILE)
  try {
    const temp = `${path}.${process.pid}.tmp`
    writeFileSync(temp, pinned, { encoding: 'utf8', mode: 0o600 })
    chmodSync(temp, 0o600)
    renameSync(temp, path)
  } catch { return args }
  return ['app-server', '-c', `model_catalog_json=${JSON.stringify(path)}`, ...args.slice(1)]
}
