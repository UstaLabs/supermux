import { existsSync, mkdirSync, readFileSync, rmSync, writeFileSync } from "fs"
import { dirname, join } from "path"
import { PLUGINS_DIR } from "../../shared/paths"
import { loadPluginsRegistry, savePluginsRegistry } from "./registry"
import type { CliScope } from "./types"
const MANIFEST = {
  name: "mux",
  version: "0.1.0",
  description: "supermux first-party plugin: skills for supermux-hosted sessions.",
  author: { name: "mux" },
  keywords: ["mux", "browser", "bootstrap"],
}

const MUX_OPENCODE_PLUGIN = `/**
 * mux-core plugin for OpenCode.ai — registers mux-core skills via skills.paths.
 */
import path from 'path';
import { fileURLToPath } from 'url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));

export const MuxPlugin = async () => {
  const muxSkillsDir = path.resolve(__dirname, '../../skills');
  return {
    config: async (config) => {
      config.skills = config.skills || {};
      config.skills.paths = config.skills.paths || [];
      if (!config.skills.paths.includes(muxSkillsDir)) {
        config.skills.paths.push(muxSkillsDir);
      }
    },
  };
};
`

function json(obj: unknown): string {
  return JSON.stringify(obj, null, 2) + "\n"
}

function writeIfChanged(path: string, content: string): boolean {
  if (existsSync(path) && readFileSync(path, "utf8") === content) return false
  mkdirSync(dirname(path), { recursive: true })
  writeFileSync(path, content)
  return true
}

export function ensureMuxCoreSkills(opts: { pluginDir?: string } = {}): boolean {
  const pluginDir = opts.pluginDir ?? join(PLUGINS_DIR, "mux-core")
  let changed = false
  changed = writeIfChanged(join(pluginDir, ".claude-plugin", "plugin.json"), json(MANIFEST)) || changed
  changed = writeIfChanged(join(pluginDir, ".codex-plugin", "plugin.json"), json({ ...MANIFEST, skills: "./skills/" })) || changed
  changed = writeIfChanged(join(pluginDir, ".cursor-plugin", "plugin.json"), json({ ...MANIFEST, displayName: "mux", skills: "./skills/" })) || changed
  changed = writeIfChanged(join(pluginDir, ".opencode", "plugins", "mux.js"), MUX_OPENCODE_PLUGIN) || changed
  // The manifests point at ./skills/, so the directory must exist even with no first-party skill in it.
  mkdirSync(join(pluginDir, "skills"), { recursive: true })
  // Instructions (incl. the reply rule) now reach every agent through the Core session
  // context; the SessionStart hook + reply-conventions skill that used to inject them are
  // removed from installs that still have them. The soul and new-personal-agent skills are
  // retired with soul.md (Agents keep their own memory; `create_agent` creates one).
  for (const stale of [
    join(pluginDir, "hooks"),
    join(pluginDir, "skills", "reply-conventions"),
    join(pluginDir, "skills", "soul"),
    join(pluginDir, "skills", "new-personal-agent"),
  ]) {
    if (existsSync(stale)) { rmSync(stale, { recursive: true, force: true }); changed = true }
  }
  return changed
}

const MUX_CORE_DIR_NAME = "mux-core"
const MUX_CORE_SCOPES: CliScope[] = ["claude", "codex", "cursor", "opencode", "grok", "antigravity"]

/**
 * Ensure the mux-core plugin is REGISTERED + enabled in the plugins registry
 * (~/.mux/plugins.json). `ensureMuxCoreSkills` only writes the plugin *files* —
 * without an enabled registry entry, `loadPluginsForSpawn` returns [] and a
 * fresh install spawns sessions with ZERO plugins (no mux skills). Run at
 * boot alongside ensureMuxCoreSkills.
 *
 * Idempotent + non-destructive: if a `mux-core` entry already exists it is left
 * exactly as-is (an explicitly-disabled plugin is never re-enabled). A malformed
 * plugins.json is left untouched. Returns true only when it adds the entry.
 */
export function ensureMuxCoreRegistered(opts: { file?: string; pluginsDir?: string } = {}): boolean {
  const pluginsDir = opts.pluginsDir ?? PLUGINS_DIR
  let reg
  try {
    reg = loadPluginsRegistry({ file: opts.file, pluginsDir })
  } catch {
    // Malformed plugins.json — never clobber the user's registry.
    return false
  }
  if (reg.plugins.some((p) => p.name === MUX_CORE_DIR_NAME)) return false
  const dir = join(pluginsDir, MUX_CORE_DIR_NAME)
  reg.plugins.push({
    name: MUX_CORE_DIR_NAME,
    version: MANIFEST.version,
    source: { type: "local", path: dir },
    enabled: true,
    scopes: MUX_CORE_SCOPES,
    dir,
  })
  savePluginsRegistry(reg, { file: opts.file, pluginsDir })
  return true
}
