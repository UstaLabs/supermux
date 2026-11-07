import { createHash } from "crypto"
import { join } from "path"
import { ANTIGRAVITY_CLI_DIR_ENV } from "../../../../packages/supermux-core/src/accounts/index.js"
import { linkAntigravityCli, prepareAntigravityEnvironment } from "../../../../packages/supermux-core/src/environment/index.js"
import { AgentKind } from "../../../shared/agents"
import { home as defaultHome } from "../../../shared/home"
import { makeLogger } from "../../../shared/log"
import { STATE_DIR } from "../../../shared/paths"
import { runCli, type DiscoveryLaunch, type ModelInfo } from "../../models/discovery"
import { antigravityUpdaterEnv } from "../../../../packages/supermux-core/src/antigravity/updater.js"

const log = makeLogger("agents/antigravity/models")

/** The failure last logged per login ("system" or an account's key): said once, not at every refresh. */
const lastCause = new Map<string, string>()

/** `agy models` prints one `id<TAB>label` per line (effort baked into the id). Lines without a tab are skipped; ids deduped. */
export function parseAgyModels(raw: string): ModelInfo[] {
  const seen = new Set<string>()
  const models: ModelInfo[] = []
  for (const line of raw.split(/\r?\n/)) {
    const tab = line.indexOf("\t")
    if (tab < 0) continue
    const id = line.slice(0, tab).trim()
    if (!id || seen.has(id)) continue
    seen.add(id)
    const label = line.slice(tab + 1).trim()
    models.push({ id, displayName: label || id, agent: AgentKind.Antigravity })
  }
  return models
}

/**
 * Runs `agy models` under a discovery shadow HOME (STATE_DIR/agents/antigravity/_discovery/<key>/home),
 * never the user's real HOME, so agy cannot write under the real ~/.gemini. The system login links the
 * user's own ~/.gemini/antigravity-cli; an account launch links its cli dir instead (one shadow per account).
 */
export async function discoverAntigravityModels(opts?: {
  launch?: DiscoveryLaunch
  run?: (env: Record<string, string>) => Promise<string>
  stateDir?: string
  userHome?: string
  platform?: NodeJS.Platform
}): Promise<ModelInfo[]> {
  const cliDir = opts?.launch?.env[ANTIGRAVITY_CLI_DIR_ENV]
  const key = cliDir ? createHash("sha256").update(cliDir).digest("hex").slice(0, 12) : "system"
  try {
    const platform = opts?.platform ?? process.platform
    const userHome = opts?.userHome ?? defaultHome()
    const shadow = join(opts?.stateDir ?? STATE_DIR, "agents", "antigravity", "_discovery", key, "home")
    await prepareAntigravityEnvironment({ home: shadow, userHome, platform })
    if (cliDir) linkAntigravityCli(shadow, cliDir, userHome, platform)
    const env: Record<string, string> = {
      ...(opts?.launch?.env ?? {}),
      HOME: shadow,
      ...(platform === "win32" ? { USERPROFILE: shadow } : {}),
      ...antigravityUpdaterEnv(platform),
    }
    const raw = opts?.run
      ? await opts.run(env)
      : await runCli(["agy"], ["models"], 30_000, { env, unset: opts?.launch?.unset, args: opts?.launch?.args })
    const models = parseAgyModels(raw)
    lastCause.delete(key)
    return models
  } catch (err) {
    // An empty picker alone doesn't say why (a signed-out agy, a shadow HOME that can't be built): say it, once.
    const cause = (err instanceof Error ? err.message : String(err)).replace(/\s+/g, " ").trim().slice(0, 300)
    if (lastCause.get(key) !== cause) {
      lastCause.set(key, cause)
      log.warn("antigravity_models_failed", { login: key, cause })
    }
    return []
  }
}
