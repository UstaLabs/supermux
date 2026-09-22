export type McpServerSpec = { name: string; command: string; args: string[]; env: Record<string, string> }

export type EnvironmentSpec = {
  home: string
  workdir: string
  sessionId: string
  sessionName: string
  mcpServers: McpServerSpec[]
  skillsPaths: string[]
  instructions: string | null
}

export type PreparedEnvironment = {
  env: Record<string, string>
  files: string[]
  credentials: "canonical" | "api_key" | "copy" | "none"
}

export type GrokEnvironmentSpec = EnvironmentSpec & {
  credentials: { canonicalAuthPath: string }
  autoUpdate: boolean
  importClaudeConfig: boolean
  platform: NodeJS.Platform
}

export type CodexEnvironmentSpec = EnvironmentSpec & {
  credentials: { apiKey: string | null; canonicalHome: string }
  nativeMemory: boolean
}

export type OpenCodeEnvironmentSpec = EnvironmentSpec & {
  /** Session-private XDG_CONFIG_HOME; config lands at <configHome>/opencode/opencode.json (0600). */
  configHome: string
  /** User global `provider` block passthrough; null = omit. */
  provider: Record<string, unknown> | null
  /** `plugin` array; [] = omit. */
  pluginPaths: string[]
}
