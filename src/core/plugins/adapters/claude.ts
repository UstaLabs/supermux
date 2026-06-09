import { join } from "path"
import { PluginDirAdapter } from "./plugin-dir-adapter"

// ClaudePluginAdapter — the simplest, fully-verified mechanism. Claude accepts
// a repeatable `--plugin-dir <path>` flag (smoke-tested 2026-05-30); each
// compatible enabled plugin contributes one. See the plugin-host spec.
export class ClaudePluginAdapter extends PluginDirAdapter {
  readonly cli = "claude" as const
  protected readonly manifestPath = join(".claude-plugin", "plugin.json")
}
