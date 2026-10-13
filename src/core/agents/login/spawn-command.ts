import { AgentKind } from "../../../shared/agents"
import { loginSpawnCommand as claudeLogin } from "../claude/auth"
import { loginSpawnCommand as codexLogin } from "../codex/auth"
import { loginSpawnCommand as cursorLogin } from "../cursor/auth"
import { loginSpawnCommand as opencodeLogin } from "../opencode/auth"
import { loginSpawnCommand as grokLogin } from "../grok/auth"
import { loginSpawnCommand as antigravityLogin } from "../antigravity/auth"

export interface LoginSpawnCommand {
  cmd: string
  args: string[]
  detached?: boolean
  /** Child env; the spawner falls back to a plain process.env copy. */
  env?: Record<string, string>
  /** Child cwd; the spawner falls back to its own. */
  cwd?: string
  /** Called once after the child exits (or fails to spawn): removes what the descriptor created. */
  cleanup?: () => void
}

/** Per-kind device-login spawn descriptors, one `loginSpawnCommand()` leaf per
 * agents/<kind>/auth.ts. null = the kind has no CLI device-login flow. */
export const loginSpawnCommands: Record<AgentKind, () => LoginSpawnCommand | null> = {
  [AgentKind.Claude]: claudeLogin,
  [AgentKind.Codex]: codexLogin,
  [AgentKind.Cursor]: cursorLogin,
  [AgentKind.OpenCode]: opencodeLogin,
  [AgentKind.Grok]: grokLogin,
  [AgentKind.Antigravity]: antigravityLogin,
}

/** POSIX single-quote one shell word. */
export function shQuote(s: string): string {
  return `'${s.replace(/'/g, `'\\''`)}'`
}

/** Claude's browser login needs a pseudo-terminal (it reads the pasted code from it). */
export function claudeLoginSpawnCommand(platform = process.platform): LoginSpawnCommand {
  return ptyLoginSpawnCommand("stty cols 600; exec claude auth login", "supermux-claude", platform)
}

/**
 * Runs [shellCommand] under a pseudo-terminal. `script` has incompatible
 * command-line syntaxes on macOS (BSD) and Linux (util-linux), so keep the
 * platform split explicit instead of relying on the Linux-only `-c` flag.
 * [label] names the wrapper's argv[0]s (`<label>-script`, `<label>-login`).
 */
export function ptyLoginSpawnCommand(shellCommand: string, label: string, platform = process.platform): LoginSpawnCommand {
  if (platform === "darwin") {
    return {
      cmd: "/bin/sh",
      // Node/Bun implements child stdin with a socketpair on macOS. BSD
      // `script` calls tcgetattr() on stdin and aborts on that socket with
      // EOPNOTSUPP. `cat | script` converts it to a real POSIX pipe while
      // keeping the stream writable for the OAuth code the user pastes later.
      // Run `script` through a supervising shell: once Claude/script exits,
      // kill the sibling `cat` that would otherwise wait on the still-open
      // socket forever and prevent the broker from observing process exit.
      // Pass the command as $1 instead of interpolating shell text.
      args: [
        "-c",
        `cat | /bin/sh -c 'trap "/usr/bin/pkill -TERM -P \\"$PPID\\" -x cat 2>/dev/null || true" EXIT; /usr/bin/script -q /dev/null /bin/sh -c "$1"' ${label}-script "$1"`,
        `${label}-login`,
        shellCommand,
      ],
      // The wrapper is a shell pipeline. Put it in its own process group so a
      // cancelled/retried login terminates cat, script, and the CLI together.
      detached: true,
    }
  }
  return {
    cmd: "script",
    args: ["-qec", shellCommand, "/dev/null"],
  }
}
