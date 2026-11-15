// #152 B5: what a client shows about where a session's work comes from: the chat crumb ("#123 · pushes update it"),
// the composer footer and #72's pane header read the pull / merge request it works on and where its pushes go.
import type { Session } from "./types"

export function sessionSourceFields(s: Partial<Pick<Session, "source_kind" | "pr_forge" | "pr_number" | "pr_url" | "push_remote">>) {
  return {
    ...(s.source_kind ? { source: s.source_kind } : {}),
    ...(s.pr_forge && s.pr_number ? { pr: { forge: s.pr_forge, number: s.pr_number, url: s.pr_url ?? "" } } : {}),
    ...(s.push_remote ? { push_remote: s.push_remote } : {}),
  }
}
