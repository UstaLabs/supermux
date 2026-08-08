// Detects, per agent CLI, whether it is installed (binary on PATH) and
// authenticated (the CLI's credential file exists at the path this codebase
// already checks). Pure + dependency-injected so it unit-tests without I/O;
// main.ts wires the real hasBinary/existsSync probes. Credential paths mirror
// the existing checks in usage/index.ts (claude), codex/auth.ts, cursor/auth.ts.
import { join, win32 } from "path"
import { AGENT_KINDS, AgentKind } from "../../shared/agents"
import { claudeIsAuthed, type AuthStatusRunner } from "./claude/auth"
import { agentAuthCapabilities, type AgentAuthCapabilities } from "./capabilities"

export interface AgentStatus {
  kind: AgentKind
  installed: boolean
  authed: boolean
  /** Kind-derived behavior flags for login UIs — clients must not branch on
   *  `kind` for behavior (see core/agents/capabilities.ts). */
  capabilities: AgentAuthCapabilities
}

export interface DetectProbes {
  hasBinary: (bin: string) => boolean
  fileExists: (path: string) => boolean
  hasCredential?: (kind: AgentKind) => boolean
  /** Injected `claude auth status` runner; claude's module owns the default. */
  claudeAuthStatus?: AuthStatusRunner
}

export interface DetectPaths {
  home: string
  xdgConfigHome?: string
  xdgDataHome?: string
  appData?: string
  localAppData?: string
  platform?: NodeJS.Platform
  /** Environment to treat as a credential source. Empty by default, so detection
   * reads no ambient state unless the caller opts in. Only claude uses it. */
  env?: Record<string, string | undefined>
}

const ALL_KINDS: readonly AgentKind[] = AGENT_KINDS

const BINARIES: Record<AgentKind, readonly string[]> = {
  claude: ["claude"],
  codex: ["codex"],
  cursor: ["cursor-agent", "agent"],
  opencode: ["opencode"],
  grok: ["grok"],
}

/** Absolute path to the credential file the broker treats as "this CLI is logged in". */
export function authCredPath(kind: AgentKind, paths: DetectPaths): string {
  const platform = paths.platform ?? process.platform
  const pathJoin = platform === "win32" ? win32.join : join
  switch (kind) {
    case "claude":
      return pathJoin(paths.home, ".claude", ".credentials.json")
    case "codex":
      return pathJoin(paths.home, ".codex", "auth.json")
    case "cursor":
      return pathJoin(platform === "win32"
        ? (paths.appData || pathJoin(paths.home, "AppData", "Roaming"))
        : (paths.xdgConfigHome || pathJoin(paths.home, ".config")), "cursor", "auth.json")
    case "opencode":
      // opencode stores multi-provider creds in its XDG data dir (written by
      // `opencode auth login`), NOT the config dir.
      return pathJoin(platform === "win32"
        ? (paths.localAppData || pathJoin(paths.home, "AppData", "Local"))
        : (paths.xdgDataHome || pathJoin(paths.home, ".local", "share")), "opencode", "auth.json")
    case "grok":
      return pathJoin(paths.home, ".grok", "auth.json")
  }
}

/** One credential probe per kind. Four kinds answer with a single file test.
 * Claude has three sources — environment, stored settings, credential file, and
 * the darwin Keychain — so `claude/auth.ts` owns claude's answer and this table
 * only calls it. A table, not a kind test: no service branches on the kind. */
const CRED_PROBE: Record<AgentKind, (probes: DetectProbes, paths: DetectPaths) => boolean> = {
  claude: (probes, paths) => claudeIsAuthed({
    home: paths.home,
    platform: paths.platform,
    env: paths.env,
    fileExists: probes.fileExists,
    storedCredential: probes.hasCredential?.(AgentKind.Claude) ?? false,
    runner: probes.claudeAuthStatus,
  }),
  codex: credFileOrStored(AgentKind.Codex),
  cursor: credFileOrStored(AgentKind.Cursor),
  opencode: credFileOrStored(AgentKind.OpenCode),
  grok: credFileOrStored(AgentKind.Grok),
}

function credFileOrStored(kind: AgentKind) {
  return (probes: DetectProbes, paths: DetectPaths): boolean =>
    probes.fileExists(authCredPath(kind, paths)) || (probes.hasCredential?.(kind) ?? false)
}

export function detectAgent(kind: AgentKind, probes: DetectProbes, paths: DetectPaths): AgentStatus {
  const installed = BINARIES[kind].some(probes.hasBinary)
  // `authed` means a real credential is present: the CLI's auth file exists (or a
  // stored credential is configured). opencode follows the SAME rule — its free
  // `opencode/*` tier runs with zero credentials, so a fresh install is `installed`
  // but NOT `authed` (no provider connected). The UI renders that free-tier state as
  // "Ready · free tier"; opencode spawning never fail-closes on auth, so this only
  // affects the status badge, not usability.
  // Step-2's CRED_PROBE table stays the auth oracle; C's capability flags ride along.
  const authed = installed && CRED_PROBE[kind](probes, paths)
  return { kind, installed, authed, capabilities: agentAuthCapabilities(kind) }
}

export function detectAllAgents(probes: DetectProbes, paths: DetectPaths): AgentStatus[] {
  return ALL_KINDS.map((k) => detectAgent(k, probes, paths))
}
