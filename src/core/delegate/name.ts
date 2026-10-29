/** Longest name derived from a task. */
const MAX = 40

/** A child's session name when delegate gets no title (spec §3): the task's first line, cut at a word. */
export function nameFromTask(task: string): string {
  const line = (task.trim().split("\n", 1)[0] ?? "").replace(/\s+/g, " ").trim()
  if (!line) return "Subagent"
  if (line.length <= MAX) return line
  const cut = line.slice(0, MAX)
  const space = cut.lastIndexOf(" ")
  return (space > 0 ? cut.slice(0, space) : cut).replace(/[\s,.;:–—-]+$/, "")
}
