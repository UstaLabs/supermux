import { existsSync, readdirSync, readFileSync } from "fs"
import { join } from "path"

/**
 * Whether grok keeps scheduled tasks for this session. Grok saves them with the session
 * (`<home>/.grok/sessions/<encoded workdir>/<session id>/resources_state.json`, the
 * `grok_build.Scheduler` state) and runs them only while the session is open: "When the session
 * is reopened, an overdue durable task runs once. Missed runs are not made up." (grok 1.0.46).
 */
export function grokHasScheduledTasks(agentHome: string, agentSessionId: string): boolean {
  const sessions = join(agentHome, ".grok", "sessions")
  if (!existsSync(sessions)) return false
  for (const workdir of readdirSync(sessions)) {
    const file = join(sessions, workdir, agentSessionId, "resources_state.json")
    if (!existsSync(file)) continue
    try {
      const tasks = JSON.parse(readFileSync(file, "utf8"))?.state?.["grok_build.Scheduler"]?.tasks
      if (Array.isArray(tasks) && tasks.length > 0) return true
    } catch { /* not JSON: grok can't run it either */ }
  }
  return false
}
