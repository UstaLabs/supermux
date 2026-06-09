import { join } from "path"
import { PluginDirAdapter } from "./plugin-dir-adapter"

// CursorPluginAdapter — same mechanism as Claude. cursor-agent accepts a
// repeatable `--plugin-dir <path>` flag (smoke-tested 2026-05-30), differing
// only in the manifest it requires (.cursor-plugin/plugin.json).
export class CursorPluginAdapter extends PluginDirAdapter {
  readonly cli = "cursor" as const
  protected readonly manifestPath = join(".cursor-plugin", "plugin.json")
}
