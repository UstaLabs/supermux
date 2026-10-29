import type { HostBinaryOptions } from "./host-binary.js"

export type McpServerSpec = { name: string; command: string; args: string[]; env: Record<string, string> }

export type EnvironmentSpec = {
  home: string
  workdir: string
  mcpServers: McpServerSpec[]
  skillsPaths: string[]
}

export type PreparedEnvironment = {
  env: Record<string, string>
  files: string[]
  credentials: "canonical" | "api_key" | "copy" | "none" | "account"
  /** Claude only: CLI args assembled from session-private files (not HOME). */
  args?: string[]
}

export type GrokEnvironmentSpec = EnvironmentSpec & {
  credentials: { canonicalAuthPath: string }
  autoUpdate: boolean
  importClaudeConfig: boolean
  platform: NodeJS.Platform
}

export type AntigravityEnvironmentSpec = {
  /** Shadow HOME to build (…/agents/antigravity/<name>/home). */
  home: string
  /** The user's real HOME (read only). */
  userHome: string
  platform: NodeJS.Platform
}

export type CodexEnvironmentSpec = EnvironmentSpec & {
  /** `account: true`: the session's credential comes from an account (env/args at launch): no copy,
   * and a copy left by an earlier launch is healed back to `canonicalHome` and removed. */
  credentials: { apiKey: string | null; canonicalHome: string; account?: boolean }
  nativeMemory: boolean
  /**
   * Optional: files the session's shell may read without asking, written as ONE exec-policy rules file,
   * `<home>/rules/<name>.rules`. Codex loads every `*.rules` file of the `rules` folder in CODEX_HOME;
   * a file that does not parse drops ALL the rules of that home, so the text is generated here
   * (see `codexReadRules`), never passed in. For each file: `cat`, `head` and `tail` followed by exactly
   * that path, and `wc -l <path>`, are allowed. A command an allow rule matches in full runs OUTSIDE Codex's
   * sandbox, so the rules name ABSOLUTE programs only: the host's real binaries, found by `trustedHostBinary`
   * (owned by root, not writable by others); a program that cannot be trusted gets no rule, and what was
   * written, and what was not, is reported in `readPrograms` / `unresolvedPrograms` of the result.
   * Any other form (`tail -n 1 <path>`, a bare `cat`) still asks; prefix matching lets extra trailing
   * arguments through (`<cat> <path> /etc/passwd`).
   * `name` starts with `supermux-` (never `default.rules`, where Codex records the commands the user
   * allows). Use one per session: CODEX_HOME can be shared between sessions, and a name that is reused
   * replaces the file (an empty `files` removes it). POSIX shells only: absolute, canonical paths.
   * `hostBinaries` is a test seam: where the programs are looked for, and who must own them.
   */
  readableFiles?: { name: string; files: string[]; hostBinaries?: HostBinaryOptions }
}

export type OpenCodeEnvironmentSpec = EnvironmentSpec & {
  /** Session-private XDG_CONFIG_HOME; config lands at <configHome>/opencode/opencode.json (0600). */
  configHome: string
  /** User global `provider` block passthrough; null = omit. */
  provider: Record<string, unknown> | null
  /** `plugin` array; [] = omit. */
  pluginPaths: string[]
  /** OpenCode's per-tool permission policy (`permission.edit/bash/webfetch`). Written only
   *  when any value is not "allow". Verified on OpenCode 1.16.2. */
  permissions: OpenCodeToolPermissions
  /** Optional: absolute folders OpenCode may touch without its outside-the-project ask. OpenCode raises an
   *  `external_directory` permission before any tool touches anything outside the project, and that ask
   *  reaches the host; each folder is written as `permission.external_directory["<dir>/*"] = "allow"`.
   *  Not read-only, whatever the name suggests: the allow waives that check for ANY tool that touches the
   *  folder, and `permissions` is written as given, so a session whose `permissions.edit` is "allow" can
   *  also write there without a prompt (the same class of caveat as Antigravity's `--add-dir`).
   *  Each entry must be a canonical folder (see `readableFolderPattern`): it is refused when nothing is
   *  left after trimming trailing slashes, when it holds `..`, `.` or `//`, or `*`, `?` or (outside Windows) `\`.
   *  No `"*": "ask"` entry goes with it: OpenCode's last matching rule wins, so it would cancel the
   *  built-in allowances (its temp folder, skill folders). Verified on OpenCode 1.18.34. */
  readableDirs?: string[]
}

export type OpenCodeToolAction = "allow" | "ask" | "deny"

export type OpenCodeToolPermissions = {
  edit: OpenCodeToolAction
  bash: OpenCodeToolAction
  webfetch: OpenCodeToolAction
}

export type ClaudeEnvironmentSpec = EnvironmentSpec & {
  // EnvironmentSpec.home is the session dir where the files below are written (created 0700); Claude's own home is untouched.
  pluginDirs: string[]
  addDirs: string[]
  systemPromptFiles: string[]
  strictMcp: boolean
  nativeMemory: boolean
}

export type CursorEnvironmentSpec = EnvironmentSpec & {
  credentials: {
    apiKey: string | null
    userCursorDir: string
    userConfigDir: string
    /** The credential comes from an account: no auth.json copy (a leftover one is healed back and
     * removed); the non-credential CLI config files are still copied. */
    account?: boolean
  }
  /** Link this home's cursor-agent runtime dir to one shared copy; null = do nothing. */
  sharedRuntime: { source: string } | null
  platform: NodeJS.Platform
}
