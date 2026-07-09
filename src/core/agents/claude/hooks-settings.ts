import { resolve } from "path"
import { existsSync, mkdirSync, readFileSync, writeFileSync } from "fs"
import { STATE_DIR } from "../../../shared/paths"

export const CLAUDE_HOOKS_SETTINGS_PATH = resolve(STATE_DIR, "claude-hooks.json")
export const INTERNAL_HOOK_SECRET_FILE = resolve(STATE_DIR, "internal-hook-secret")

const LIFECYCLE_HOOK_EVENTS = ["UserPromptSubmit", "PreToolUse", "PostToolUse", "Stop", "StopFailure"] as const

export function readPersistedHookSecret(): string {
  try { return readFileSync(INTERNAL_HOOK_SECRET_FILE, "utf8").trim() } catch { return "" }
}

export function writePersistedHookSecret(secret: string): void {
  mkdirSync(resolve(STATE_DIR), { recursive: true })
  writeFileSync(INTERNAL_HOOK_SECRET_FILE, secret, "utf8")
}

/** Boot-time secret for the /internal/agent-hook endpoint. MUST be stable
 *  across broker restarts: Claude Code snapshots hook config at CLI startup,
 *  so a session that outlives a restart keeps curling with the secret it was
 *  born with — a per-boot secret 403s all of those hooks silently and their
 *  sessions' statuses freeze at "idle". Reuse the persisted secret; generate
 *  one only on first boot (or a wiped state dir). */
export function resolveInternalHookSecret(generate: () => string): string {
  const persisted = readPersistedHookSecret()
  if (persisted) return persisted
  const fresh = generate()
  writePersistedHookSecret(fresh)
  return fresh
}

/** True when claude-hooks.json curl commands embed ?s= (broker requires matching secret). */
export function hooksFileUsesHookSecret(path = CLAUDE_HOOKS_SETTINGS_PATH): boolean {
  if (!existsSync(path)) return false
  try {
    const cfg = JSON.parse(readFileSync(path, "utf8")) as { hooks?: Record<string, Array<{ hooks?: Array<{ command?: string }> }>> }
    for (const ev of LIFECYCLE_HOOK_EVENTS) {
      for (const entry of cfg.hooks?.[ev] ?? []) {
        for (const h of entry.hooks ?? []) {
          const cmd = h.command
          if (typeof cmd === "string" && cmd.includes("/internal/agent-hook/") && cmd.includes("?s=")) return true
        }
      }
    }
  } catch { /* corrupt file → treat as legacy */ }
  return false
}

export function writeClaudeHooksSettings(webPort: number, internalSecret?: string): string {
  const secret = internalSecret !== undefined ? internalSecret : readPersistedHookSecret()
  if (secret) writePersistedHookSecret(secret)
  const q = secret ? `?s=${secret}` : ""
  const makeCmd = (event: string) =>
    `curl -s --max-time 2 -X POST -H 'Content-Type: application/json' --data-binary @- http://127.0.0.1:${webPort}/internal/agent-hook/${event}${q} >/dev/null 2>&1 || true`

  // SessionStart: give a PERSONAL ASSISTANT its full identity by injecting its
  // soul.md (name + persona) as additionalContext — the high-salience in-session
  // channel, so it's known from token one. WORKERS get nothing (soul.md is PA-only).
  const soulContextCmd =
    `bun -e 'if(process.env.MUX_SESSION_ROLE!=="main")process.exit(0);` +
    `const fs=require("fs");const p=(process.env.MUX_HOME||((process.env.HOME||"")+"/.mux"))+"/soul.md";` +
    `let s="";try{s=fs.readFileSync(p,"utf8").trim()}catch{}if(!s)process.exit(0);` +
    `process.stdout.write(JSON.stringify({hookSpecificOutput:{hookEventName:"SessionStart",` +
    `additionalContext:"This is who you are in this session — your identity, from your soul.md. Embody it; ` +
    `when the user asks your name, answer with the name here, not Claude or Claude Code:\\n\\n"+s}}))'`

  const askDenyJson = JSON.stringify({
    hookSpecificOutput: {
      hookEventName: "PreToolUse",
      permissionDecision: "deny",
      permissionDecisionReason:
        "AskUserQuestion is unavailable in this environment — its prompt renders in a terminal the user cannot see. To ask the user, call your reply tool (the MCP reply tool provided by your channel server, e.g. mcp__*__reply) with the chat_id, phrasing the options as plain text.",
    },
  })

  const settings = {
    enabledPlugins: {
      "telegram@claude-plugins-official": false,
    },
    hooks: {
      SessionStart:     [{ hooks: [{ type: "command", command: soulContextCmd }] }],
      UserPromptSubmit: [{ hooks: [{ type: "command", command: makeCmd("UserPromptSubmit") }] }],
      PreToolUse: [
        { hooks: [{ type: "command", command: makeCmd("PreToolUse") }] },
        { matcher: "AskUserQuestion", hooks: [{ type: "command", command: `printf '%s' '${askDenyJson}'` }] },
      ],
      PostToolUse:   [{ hooks: [{ type: "command", command: makeCmd("PostToolUse") }] }],
      Stop:          [{ hooks: [{ type: "command", command: makeCmd("Stop") }] }],
      StopFailure:   [{ hooks: [{ type: "command", command: makeCmd("StopFailure") }] }],
    },
  }

  mkdirSync(resolve(STATE_DIR), { recursive: true })
  writeFileSync(CLAUDE_HOOKS_SETTINGS_PATH, JSON.stringify(settings, null, 2), "utf8")
  return CLAUDE_HOOKS_SETTINGS_PATH
}
