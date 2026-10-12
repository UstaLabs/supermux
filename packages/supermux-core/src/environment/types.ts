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

export type CodexEnvironmentSpec = EnvironmentSpec & {
  /** `account: true`: the session's credential comes from an account (env/args at launch): no copy,
   * and a copy left by an earlier launch is healed back to `canonicalHome` and removed. */
  credentials: { apiKey: string | null; canonicalHome: string; account?: boolean }
  nativeMemory: boolean
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
