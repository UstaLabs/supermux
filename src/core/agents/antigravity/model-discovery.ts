import { createHash } from "crypto"
import { join } from "path"
import { ANTIGRAVITY_CLI_DIR_ENV } from "../../../../packages/supermux-core/src/accounts/index.js"
import { linkAntigravityCli, prepareAntigravityEnvironment } from "../../../../packages/supermux-core/src/environment/index.js"
import { AgentKind } from "../../../shared/agents"
import { home as defaultHome } from "../../../shared/home"
import { STATE_DIR } from "../../../shared/paths"
import { runCli, type DiscoveryLaunch, type ModelInfo } from "../../models/discovery"

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
}): Promise<ModelInfo[]> {
  try {
    const platform = process.platform
    const userHome = opts?.userHome ?? defaultHome()
    const cliDir = opts?.launch?.env[ANTIGRAVITY_CLI_DIR_ENV]
    const key = cliDir ? createHash("sha256").update(cliDir).digest("hex").slice(0, 12) : "system"
    const shadow = join(opts?.stateDir ?? STATE_DIR, "agents", "antigravity", "_discovery", key, "home")
    await prepareAntigravityEnvironment({ home: shadow, userHome, platform })
    if (cliDir) linkAntigravityCli(shadow, cliDir, userHome, platform)
    const env: Record<string, string> = {
      ...(opts?.launch?.env ?? {}),
      HOME: shadow,
      ...(platform === "win32" ? { USERPROFILE: shadow } : {}),
    }
    const raw = opts?.run
      ? await opts.run(env)
      : await runCli(["agy"], ["models"], 30_000, { env, unset: opts?.launch?.unset, args: opts?.launch?.args })
    return parseAgyModels(raw)
  } catch {
    return []
  }
}
