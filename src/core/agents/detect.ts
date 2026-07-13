// Detects, per agent CLI, whether it is installed (binary on PATH) and
// authenticated (the CLI's credential file exists at the path this codebase
// already checks). Pure + dependency-injected so it unit-tests without I/O;
// main.ts wires the real hasBinary/existsSync probes. Credential paths mirror
// the existing checks in usage/index.ts (claude), codex/auth.ts, cursor/auth.ts.
import { join } from "path"
import { AGENT_KINDS, AgentKind } from "../../shared/agents"

export interface AgentStatus {
  kind: AgentKind
  installed: boolean
  authed: boolean
}

export interface DetectProbes {
  hasBinary: (bin: string) => boolean
  fileExists: (path: string) => boolean
  hasCredential?: (kind: AgentKind) => boolean
}

export interface DetectPaths {
  home: string
  xdgConfigHome?: string
  xdgDataHome?: string
}

const ALL_KINDS: readonly AgentKind[] = AGENT_KINDS

const BINARY: Record<AgentKind, string> = {
  claude: "claude",
  codex: "codex",
  cursor: "cursor-agent",
  opencode: "opencode",
  grok: "grok",
}

/** Absolute path to the credential file the broker treats as "this CLI is logged in". */
export function authCredPath(kind: AgentKind, paths: DetectPaths): string {
  switch (kind) {
    case "claude":
      return join(paths.home, ".claude", ".credentials.json")
    case "codex":
      return join(paths.home, ".codex", "auth.json")
    case "cursor":
      return join(paths.xdgConfigHome || join(paths.home, ".config"), "cursor", "auth.json")
    case "opencode":
      // opencode stores multi-provider creds in its XDG data dir (written by
      // `opencode auth login`), NOT the config dir.
      return join(paths.xdgDataHome || join(paths.home, ".local", "share"), "opencode", "auth.json")
    case "grok":
      return join(paths.home, ".grok", "auth.json")
  }
}

export function detectAgent(kind: AgentKind, probes: DetectProbes, paths: DetectPaths): AgentStatus {
  const installed = probes.hasBinary(BINARY[kind])
  // `authed` means a real credential is present: the CLI's auth file exists (or a
  // stored credential is configured). opencode follows the SAME rule — its free
  // `opencode/*` tier runs with zero credentials, so a fresh install is `installed`
  // but NOT `authed` (no provider connected). The UI renders that free-tier state as
  // "Ready · free tier"; opencode spawning never fail-closes on auth, so this only
  // affects the status badge, not usability.
  const authed = installed && (probes.fileExists(authCredPath(kind, paths)) || (probes.hasCredential?.(kind) ?? false))
  return { kind, installed, authed }
}

export function detectAllAgents(probes: DetectProbes, paths: DetectPaths): AgentStatus[] {
  return ALL_KINDS.map((k) => detectAgent(k, probes, paths))
}
