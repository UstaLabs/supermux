import { helperBinaries, verifyHelperManifest, zmxBinDir } from "../core/terminal/zmx/helper"
import { resolveCommand } from "../core/process/launcher"

export interface PreflightResult {
  fatal: string[]
  warnings: string[]
}

/**
 * Can this host open a WORKSPACE terminal?
 *
 * Before Plan 4 the answer was `which tmux`, and that is no longer a question
 * about workspace terminals at all: they run on a pinned, patched zmx whose
 * daemon and framed helper this build ships and verifies against a manifest. A
 * host with tmux installed and no bundle cannot open one; a host with no tmux
 * and a good bundle opens them fine. So the readiness check asks the packaging
 * question — are the artifacts THIS build was tested against actually here, and
 * are they the ones the manifest describes — and never asks PATH anything.
 */
export type WorkspaceTerminalReadiness =
  | { ok: true; detail: string }
  | { ok: false; reason: string }

const AGENT_CLIS = [
  { label: "claude", names: ["claude"] },
  { label: "codex", names: ["codex"] },
  { label: "cursor-agent", names: ["cursor-agent", "agent"] },
  { label: "opencode", names: ["opencode"] },
  { label: "grok", names: ["grok"] },
] as const

/**
 * Pure: given a "is this binary on PATH?" probe and a workspace-terminal
 * readiness answer, decide fatals/warnings.
 *
 * Both probes are injected, and `workspace` defaults to "not asked" rather than
 * to a filesystem call, so this stays a decision function and its tests stay
 * hermetic.
 */
export function checkPreflight(
  has: (bin: string) => boolean,
  platform: NodeJS.Platform = process.platform,
  workspace: WorkspaceTerminalReadiness | null = null,
): PreflightResult {
  const fatal: string[] = []
  const warnings: string[] = []

  // tmux is an AGENT-session dependency now, and only that. It stops being one
  // when the Claude native-terminal retirement lands; until then the warning has
  // to name what it actually costs, because "terminals are disabled" was read by
  // users as "the terminal feature is off", which is no longer true.
  if (platform !== "win32" && !has("tmux")) {
    warnings.push(
      "tmux not found on PATH — Claude agent sessions are disabled on this host; codex/cursor/opencode " +
        "and workspace terminals still work. Install tmux to enable them.",
    )
  }

  if (workspace && !workspace.ok) {
    warnings.push(
      `Workspace terminals are unavailable: ${workspace.reason}. ` +
        "Agent sessions are unaffected. A packaged release carries this backend; a source checkout builds it with scripts/build-zmx.sh.",
    )
  }

  const present = AGENT_CLIS.filter((cli) => cli.names.some(has))
  if (present.length === 0) {
    fatal.push(`No agent CLI found on PATH — install at least one of: ${AGENT_CLIS.map((cli) => cli.label).join(", ")}.`)
  } else {
    for (const cli of AGENT_CLIS) {
      if (!cli.names.some(has)) warnings.push(`Optional agent CLI '${cli.label}' not found on PATH — sessions using it will fail to spawn.`)
    }
  }

  return { fatal, warnings }
}

/** Real PATH probe used at boot. */
export function hasBinary(bin: string): boolean {
  return resolveCommand([bin], process.env, process.platform) !== null
}

/**
 * Real workspace-terminal probe used at boot: the broker's OWN verification, not
 * a second opinion about it. `verifyHelperManifest` re-hashes both binaries
 * against the manifest beside them, so a bundle that passes here is one that
 * will still pass at the first attach — and one that fails says why in the same
 * words the terminal error would.
 *
 * Windows has no bundle and needs none: persistent terminals there are
 * sessiond's, which the broker owns directly.
 */
export function workspaceTerminalReadiness(platform: NodeJS.Platform = process.platform): WorkspaceTerminalReadiness {
  if (platform === "win32") return { ok: true, detail: "sessiond (Windows)" }
  try {
    const dir = zmxBinDir()
    const manifest = verifyHelperManifest(helperBinaries(dir))
    return {
      ok: true,
      detail: `zmx ${manifest.zmx.commit.slice(0, 12)} helper ABI ${manifest.abi} (${manifest.target}) from ${dir}`,
    }
  } catch (error) {
    return { ok: false, reason: error instanceof Error ? error.message : String(error) }
  }
}
