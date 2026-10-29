export type {
  AntigravityEnvironmentSpec,
  ClaudeEnvironmentSpec,
  CodexEnvironmentSpec,
  CursorEnvironmentSpec,
  EnvironmentSpec,
  GrokEnvironmentSpec,
  OpenCodeEnvironmentSpec,
  OpenCodeToolAction,
  OpenCodeToolPermissions,
  McpServerSpec,
  PreparedEnvironment,
} from "./types.js"
export { requireSpec } from "./spec.js"
export { trustedHostBinary, type HostBinaryOptions } from "./host-binary.js"
export { prepareGrokEnvironment } from "./grok.js"
export {
  ANTIGRAVITY_RESERVED_PREFIX,
  type AntigravityPluginWrapper,
  assertAccountCliDir,
  assertShadowHome,
  linkAntigravityCli,
  prepareAntigravityEnvironment,
  shareAntigravityHistory,
  writeAntigravityMcpConfig,
  writeAntigravityPlugins,
} from "./antigravity.js"
export { prepareCodexEnvironment, codexReadRules, type CodexReadReport, type ReadPrograms } from "./codex.js"
export { prepareOpenCodeEnvironment } from "./opencode.js"
export { prepareCursorEnvironment } from "./cursor.js"
export { prepareClaudeEnvironment } from "./claude.js"
export { ensureSharedCursorRuntime, sharedCursorDir, cursorRuntimeRel } from "./cursor-runtime.js"
export {
  type FreshnessReader,
  type PromotionResult,
  promoteCredential,
  promoteIfNewer,
  releaseSessionCredential,
  refreshSessionCredential,
  jwtExpiryMs,
  readCredentialJson,
  grokCredentialExpiry,
  codexCredentialFreshness,
  cursorCredentialFreshness,
} from "./credentials.js"
