import type { AgentKind } from "./types"

const STEM_MAP: Record<string, string> = {
  shell: "Bash", command_execution: "Bash", commandexecution: "Bash",
  run: "Bash", runterminalcmd: "Bash", terminal: "Bash", bash: "Bash",
  read: "Read", file_read: "Read",
  edit: "Edit", file_change: "Edit", filechange: "Edit", apply_patch: "Edit", applypatch: "Edit", patch: "Edit",
  // Grok Build / Cursor-style string-replace tools — not Grep "search"
  search_replace: "Edit", searchreplace: "Edit",
  str_replace: "Edit", strreplace: "Edit", string_replace: "Edit", stringreplace: "Edit",
  multi_edit: "Edit", multiedit: "Edit",
  write: "Write", create: "Write",
  grep: "Grep", search: "Grep", codebase_search: "Grep", codebasesearch: "Grep",
  ls: "Glob", list: "Glob", glob: "Glob", listdir: "Glob",
  web: "WebFetch", websearch: "WebFetch", web_search: "WebFetch", fetch: "WebFetch",
  delete: "Delete", mcp_tool_call: "Tool", mcptoolcall: "Tool",
}

function capitalize(s: string): string { return s ? s[0]!.toUpperCase() + s.slice(1) : s }

export function normalizeToolName(_agent: AgentKind, raw: string): string {
  if (!raw) return "tool"
  let stem = raw.replace(/ToolCall$/i, "")
  // MCP tool names from opencode are `mcp__<server>__<tool>`; extract the tool part.
  if (stem.startsWith("mcp__")) {
    const parts = stem.split("__")
    if (parts.length >= 3) return capitalize(parts[parts.length - 1] ?? stem)
    return "Tool"
  }
  const key = stem.toLowerCase()
  if (STEM_MAP[key]) return STEM_MAP[key]
  // Also match underscored/camel variants after stripping separators
  const compact = key.replace(/[_-]/g, "")
  if (STEM_MAP[compact]) return STEM_MAP[compact]
  const rawKey = raw.toLowerCase()
  if (STEM_MAP[rawKey]) return STEM_MAP[rawKey]
  return capitalize(stem) || "tool"
}
