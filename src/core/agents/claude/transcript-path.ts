import { homedir } from "os"
import { join } from "path"

// Claude Code encodes a project's absolute cwd into its transcript directory
// name by replacing every "/" and "." with "-". Verified against real dirs
// under ~/.claude/projects (e.g. "/home/user/projects/myapp" ->
// "-home-user-projects-myapp").
export function encodeProjectDir(cwd: string): string {
  return cwd.replace(/\/$/, "").replace(/[/.]/g, "-")
}

export function claudeTranscriptPath(cwd: string, claudeSessionId: string): string {
  return join(homedir(), ".claude", "projects", encodeProjectDir(cwd), `${claudeSessionId}.jsonl`)
}
