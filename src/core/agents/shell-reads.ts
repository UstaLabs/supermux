import { posix } from "path"
import type { AgentKind } from "../../shared/agents"
import { isPlainShellWord } from "../../shared/shell-word"

/** The program to type for each shell read of the session's own inbox file; absent = that command is not allowed without a prompt. */
export type ShellReads = { cat?: string; head?: string; tail?: string; wc?: string }

const BARE: Required<ShellReads> = { cat: "cat", head: "head", tail: "tail", wc: "wc" }

/**
 * The shell reads of its own inbox `file` that a session of `agent` may run without a prompt, and how each is typed.
 * ONE answer for both the permission rules and the preamble, so the text can never offer a command the rules do not allow.
 * - codex: only the absolute host binaries its exec-policy rules were written for (`hostPrograms`): a rule for a bare name
 *   would also allow a planted executable of that name, and run it outside the sandbox.
 * - claude: bare names, but only for a path a shell takes unquoted (its exact Bash rules match the command text as typed).
 * - grok: bare names (`cat`, `head`, `tail` and `wc` are on its built-in read-only list).
 * - every other kind: none (Cursor's shell allowlist is `ls`, `echo` and `sleep`; OpenCode's bash asks; Antigravity is
 *   unverified). Their file-read tools need no prompt.
 * Only a canonical absolute POSIX file path has shell reads at all (no Windows paths).
 */
export function shellReadsFor(agent: AgentKind | undefined, file: string, hostPrograms: ShellReads = {}): ShellReads {
  if (file === "/" || posix.resolve(file) !== file) return {}
  switch (agent) {
    case "codex": return { ...hostPrograms }
    case "claude": return isPlainShellWord(file) ? { ...BARE } : {}
    case "grok": return { ...BARE }
    default: return {}
  }
}
