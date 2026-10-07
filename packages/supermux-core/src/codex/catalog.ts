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
 *
 * Feature detection: this rewrite relies on an undocumented catalog shape. Before
 * rewriting, `inspectCatalog` checks that the dump still is `{ models: [...] }`
 * and that the models still carry `multi_agent_version` with the values we know.
 * If not, the rewrite is skipped with a warning (the driver shows it on the
 * first turn); children then report their real protocol through `thread/read`
 * `canAcceptDirectInput` (v2: Message off, "Codex doesn't accept messages…").
 */
export const CATALOG_FILE = 'supermux-model-catalog.json'

export type CatalogInspection =
  | { kind: 'pin'; catalog: string }
  /** Every model already uses v1: nothing to do. */
  | { kind: 'v1' }
  /** The format changed: skip the rewrite and warn. */
  | { kind: 'unknown'; warning: string }

const KNOWN_VERSIONS = new Set(['v1', 'v2'])
const SKIP = 'supermux left the Codex model catalog unchanged, so subagents may use multi_agent v2 (which cannot take messages from supermux)'

export function inspectCatalog(raw: string): CatalogInspection {
  let catalog: any
  try { catalog = JSON.parse(raw) } catch { return { kind: 'unknown', warning: `${SKIP}: \`codex debug models\` did not print JSON.` } }
  const models = catalog?.models
  if (!Array.isArray(models) || !models.length) return { kind: 'unknown', warning: `${SKIP}: \`codex debug models\` no longer lists \`models\`.` }
  const versions = models.map((model: any) => model && typeof model === 'object' ? model.multi_agent_version : undefined)
  if (!versions.some((v: unknown) => v !== undefined)) return { kind: 'unknown', warning: `${SKIP}: the catalog has no \`multi_agent_version\` field any more.` }
  const strange = versions.find((v: unknown) => v !== undefined && !KNOWN_VERSIONS.has(v as string))
  if (strange !== undefined) return { kind: 'unknown', warning: `${SKIP}: unknown \`multi_agent_version\` ${JSON.stringify(strange)}.` }
  let changed = false
  for (const model of models) {
    if (model && typeof model === 'object' && model.multi_agent_version === 'v2') { model.multi_agent_version = 'v1'; changed = true }
  }
  return changed ? { kind: 'pin', catalog: JSON.stringify(catalog) } : { kind: 'v1' }
}

export function pinCatalogToV1(raw: string): string | undefined {
  const inspected = inspectCatalog(raw)
  return inspected.kind === 'pin' ? inspected.catalog : undefined
}

function dumpCatalog(command: string, env: Record<string, string | undefined>, cwd: string, timeoutMs: number): Promise<string | undefined> {
  return new Promise(resolve => {
    try {
      execFile(command, ['debug', 'models'], { env: env as NodeJS.ProcessEnv, cwd, timeout: timeoutMs, maxBuffer: 64 * 1024 * 1024, encoding: 'utf8' }, (error, stdout) => resolve(error ? undefined : stdout))
    } catch { resolve(undefined) }
  })
}

/**
 * The app-server args with the v1 catalog override (or `args` unchanged), plus a warning when the
 * override was wanted but had to be skipped (catalog dump failed or its format changed).
 */
export async function multiAgentV1Launch(command: string, args: string[], env: Record<string, string | undefined>, cwd: string, timeoutMs = 15_000): Promise<{ args: string[]; warning?: string }> {
  // Only the real CLI shape (`codex app-server …`) with a session-private home.
  if (args[0] !== 'app-server') return { args }
  const home = env.CODEX_HOME
  if (!home) return { args }
  if (args.some(arg => arg.includes('model_catalog_json'))) return { args }
  const raw = await dumpCatalog(command, env, cwd, timeoutMs)
  if (raw === undefined) return { args, warning: `${SKIP}: \`codex debug models\` failed.` }
  const inspected = inspectCatalog(raw)
  if (inspected.kind === 'unknown') return { args, warning: inspected.warning }
  if (inspected.kind === 'v1') return { args }
  const path = join(home, CATALOG_FILE)
  try {
    const temp = `${path}.${process.pid}.tmp`
    writeFileSync(temp, inspected.catalog, { encoding: 'utf8', mode: 0o600 })
    chmodSync(temp, 0o600)
    renameSync(temp, path)
  } catch { return { args, warning: `${SKIP}: could not write ${path}.` } }
  return { args: ['app-server', '-c', `model_catalog_json=${JSON.stringify(path)}`, ...args.slice(1)] }
}

/** Returns the app-server args with the v1 catalog override, or `args` unchanged. */
export async function multiAgentV1Args(command: string, args: string[], env: Record<string, string | undefined>, cwd: string, timeoutMs = 15_000): Promise<string[]> {
  return (await multiAgentV1Launch(command, args, env, cwd, timeoutMs)).args
}
