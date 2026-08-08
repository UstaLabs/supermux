import { chmodSync, existsSync, mkdirSync, readFileSync, writeFileSync } from "fs"
import { dirname, join } from "path"
import { PLUGINS_DIR } from "../../shared/paths"
import { loadPluginsRegistry, savePluginsRegistry } from "./registry"
import type { CliScope } from "./types"
// Vendored mux-core reply assets (real files → byte-faithful, no template-string
// escaping). Bun embeds the content at build time; ensureMuxCoreSkills writes
// them into the plugin dir so a fresh install ships the reply machinery itself.
import MUX_REPLY_CONVENTIONS_SKILL from "./mux-core-assets/skills/reply-conventions/SKILL.md" with { type: "text" }
import MUX_RUN_HOOK_CMD from "./mux-core-assets/hooks/run-hook.cmd" with { type: "text" }
import MUX_SESSION_START_HOOK from "./mux-core-assets/hooks/session-start" with { type: "text" }

const MANIFEST = {
  name: "mux",
  version: "0.1.0",
  description: "supermux first-party plugin: reply conventions, browser, media, and session bootstrap for supermux-hosted sessions.",
  author: { name: "mux" },
  keywords: ["mux", "reply", "browser", "bootstrap"],
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

export const MUX_SOUL_SKILL = `---
name: soul
description: Set up or revise the personal assistant's soul.md identity through a PA-only conversation.
---

# mux:soul - PA Identity Setup

Use this skill only in the personal-assistant session. It is PA-only because it may read and write \`~/.mux/soul.md\`, which workers must not read or modify.

## Guardrail

Before doing anything, check whether this session is the personal assistant. If the session context says this is a worker, refuse briefly and tell the user to run \`/mux:soul\` in their personal assistant session.

## Opening

Say that you can set up the PA identity now or skip it for later. Ask the user whether they want to continue or skip.

If they skip, write:

\`~/.mux/state/soul-setup.json\`

with:

\`{ "status": "skipped", "updatedAt": "<current ISO timestamp>" }\`

Then stop.

## Interview

If they continue, ask one question at a time. Cover only durable identity information:

- PA identity: name, role, preferred vibe.
- Communication style: concise vs detailed, direct vs warm, humor level, amount of pushback.
- Decision behavior: how to handle uncertainty, disagreement, weak ideas, and tradeoffs.
- User preferences: what to call the user, timezone if useful, stable preferences.
- Boundaries: what requires confirmation, especially external actions or personal data.
- What to avoid: filler phrases, corporate tone, verbosity, sycophancy, and pet phrases.
- Optional stable context: recurring projects or responsibilities.

Do not put project-specific rules, commands, ports, file paths, or repo workflow into \`soul.md\`; those belong in project \`AGENTS.md\`.

## Write

After the interview, write \`~/.mux/soul.md\` directly. Keep it concise. Use sections:

- Identity
- Communication Style
- Decision Behavior
- Boundaries
- User Preferences
- Avoid

Then write \`~/.mux/state/soul-setup.json\` with:

\`{ "status": "completed", "updatedAt": "<current ISO timestamp>" }\`

Tell the user that \`soul.md\` was updated.
`

export const MUX_NEW_PERSONAL_AGENT_SKILL = `---
name: new-personal-agent
description: Create a new personal assistant by talking to the existing PA. PA-only.
---

# mux:new-personal-agent - Conversational PA Creation

Use this skill only in the personal-assistant session. It is PA-only because it spawns new PAs and writes session identity files, which workers must not do.

## Guardrail

Before doing anything, check whether this session is the personal assistant and has \`can_orchestrate\`. If the session context says this is a worker, refuse briefly and tell the user to run \`/mux:new-personal-agent\` in their personal assistant session.

## Name Check

If the user provides a name, verify it is not already in use by an existing session (check the session registry). If it is, tell the user and stop. If no name is provided, ask for one.

## Conversation Flow

Ask one question at a time:

1. **Agent backend:** Which agent should the new PA use? (claude / codex / cursor / opencode / grok)
2. **Model:** Which model? (skip if the user doesn't care or if the backend doesn't require one)
3. **Focus / specialization:** What should this PA focus on? (optional — skip if none)
4. **Soul:** Should the new PA have its own \`soul.md\` in its workspace, or inherit the shared \`~/.mux/soul.md\`?
   - If "override", run a short interview about personality (same areas as \`mux:soul\`: identity, communication style, decision behavior, boundaries, user preferences, avoid). Then write \`<workdir>/soul.md\` directly.
   - If "inherit", do not write a \`soul.md\`; the new PA will pick up the shared soul at runtime.

## File Writes

- \`<workdir>/focus.md\` — if the user provided a focus in step 3.
- \`<workdir>/soul.md\` — if the user chose "override" (after the personality interview).
- No file = inherit shared soul.

The workdir for the new PA is \`~/.mux/workspace/<name>\`.

## Spawn

After writing the files, spawn the new PA by calling the \`spawn_session\` orchestration tool with:
- \`workdir\`: \`~/.mux/workspace/<name>\`
- \`name\`: the chosen name
- \`agent\`: the chosen backend
- \`chat_id\`: the current chat id (so the user's chat auto-switches to the new PA)

Then tell the user the new PA is ready and active.
`

// Claude Code / the SDK auto-discover hooks/hooks.json inside a plugin dir. This
// wires the SessionStart hook to the cross-platform run-hook.cmd wrapper, which
// execs the session-start script (it injects the reply conventions). The matcher
// fires on fresh start, /clear, and post-compact.
const MUX_HOOKS_JSON = {
  hooks: {
    SessionStart: [
      {
        matcher: "startup|clear|compact",
        hooks: [{ type: "command", command: '"${CLAUDE_PLUGIN_ROOT}/hooks/run-hook.cmd" session-start', async: false }],
      },
    ],
  },
}

// Cursor reads its hook wiring from the path named in .cursor-plugin/plugin.json
// (hooks: "./hooks/hooks-cursor.json") — same wrapper, snake_case event name.
const MUX_HOOKS_CURSOR_JSON = {
  version: 1,
  hooks: { sessionStart: [{ command: "./hooks/run-hook.cmd session-start" }] },
}

function json(obj: unknown): string {
  return JSON.stringify(obj, null, 2) + "\n"
}

function writeIfChanged(path: string, content: string, mode?: number): boolean {
  if (existsSync(path) && readFileSync(path, "utf8") === content) {
    // Content already correct — still enforce the mode so a prior write that
    // dropped the +x bit can't leave a hook script non-executable (which would
    // silently disable SessionStart injection).
    if (mode !== undefined) chmodSync(path, mode)
    return false
  }
  mkdirSync(dirname(path), { recursive: true })
  writeFileSync(path, content)
  if (mode !== undefined) chmodSync(path, mode)
  return true
}

export function ensureMuxCoreSkills(opts: { pluginDir?: string } = {}): boolean {
  const pluginDir = opts.pluginDir ?? join(PLUGINS_DIR, "mux-core")
  let changed = false
  changed = writeIfChanged(join(pluginDir, ".claude-plugin", "plugin.json"), json(MANIFEST)) || changed
  changed = writeIfChanged(join(pluginDir, ".codex-plugin", "plugin.json"), json({ ...MANIFEST, skills: "./skills/" })) || changed
  changed = writeIfChanged(join(pluginDir, ".cursor-plugin", "plugin.json"), json({ ...MANIFEST, displayName: "mux", skills: "./skills/", hooks: "./hooks/hooks-cursor.json" })) || changed
  changed = writeIfChanged(join(pluginDir, ".opencode", "plugins", "mux.js"), MUX_OPENCODE_PLUGIN) || changed
  changed = writeIfChanged(join(pluginDir, "skills", "soul", "SKILL.md"), MUX_SOUL_SKILL) || changed
  changed = writeIfChanged(join(pluginDir, "skills", "new-personal-agent", "SKILL.md"), MUX_NEW_PERSONAL_AGENT_SKILL) || changed
  // Reply delivery: the reply-conventions skill + the SessionStart hook that
  // injects it. WITHOUT these, a fresh install spawns Claude sessions that never
  // learn to call the reply tool — replies stay in the transcript, unseen by the
  // user. The two hook scripts must be executable: hooks.json invokes
  // run-hook.cmd directly, which then execs session-start.
  changed = writeIfChanged(join(pluginDir, "skills", "reply-conventions", "SKILL.md"), MUX_REPLY_CONVENTIONS_SKILL) || changed
  changed = writeIfChanged(join(pluginDir, "hooks", "hooks.json"), json(MUX_HOOKS_JSON)) || changed
  changed = writeIfChanged(join(pluginDir, "hooks", "hooks-cursor.json"), json(MUX_HOOKS_CURSOR_JSON)) || changed
  changed = writeIfChanged(join(pluginDir, "hooks", "run-hook.cmd"), MUX_RUN_HOOK_CMD, 0o755) || changed
  changed = writeIfChanged(join(pluginDir, "hooks", "session-start"), MUX_SESSION_START_HOOK, 0o755) || changed
  return changed
}

const MUX_CORE_DIR_NAME = "mux-core"
const MUX_CORE_SCOPES: CliScope[] = ["claude", "codex", "cursor", "opencode", "grok"]

/**
 * Ensure the mux-core plugin is REGISTERED + enabled in the plugins registry
 * (~/.mux/plugins.json). `ensureMuxCoreSkills` only writes the plugin *files* —
 * without an enabled registry entry, `loadPluginsForSpawn` returns [] and a
 * fresh install spawns sessions with ZERO plugins (no `/mux:soul`, no mux
 * skills). Run at boot alongside ensureMuxCoreSkills.
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

/** @deprecated use ensureMuxCoreSkills */
export const ensureMuxCoreSoulSkill = ensureMuxCoreSkills
