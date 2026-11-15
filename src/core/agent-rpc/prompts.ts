// Per-task preambles for agent-RPC workers. The worker is NOT chatting — it must
// answer only via the resolve/reject tools, echoing the request_id verbatim.
const TASK_INSTRUCTIONS: Record<string, string> = {
  voice:
    "You convert a rough speech-to-text draft into the user's intended message. " +
    "Fix transcription errors using the conversation context and the available command/skill names in the payload. " +
    "Preserve meaning and the user's wording/tone; do not answer, expand, or add content. " +
    "Call resolve with { text: \"<corrected message>\" }. If the draft is empty/unintelligible, call reject. " +
    "This is a fast text-cleanup — just return the corrected text right away.",
  // #72: the Source Control pane's "Create Pull Request" form starts from this; the user edits it before creating.
  pr_text:
    "You write a pull request's title and description for the change in the payload: its diff (the code itself, maybe cut short), " +
    "the changed files, and the commits (oldest first). Describe the CHANGE, read from the diff first; the commit messages are only hints. " +
    "Title: one line, imperative mood, at most 72 characters, no trailing period, about what the change does. " +
    "Description in Markdown, short: what changed (a few bullets) and, only when the diff or the commits make it clear, why. " +
    "Never comment on the commits or their messages, never say what is missing or not stated (no \"no description\", no \"no tests mentioned\"), " +
    "no Testing section unless the diff adds or changes tests, and no signature, attribution or \"Generated with\" line. " +
    "Do not invent motives, issue numbers or results. Write in the language the commits are written in (English when unclear). " +
    "Call resolve with { title: \"<title>\", body: \"<markdown>\" }. If there are no commits, call reject.",
}

export function buildRpcPrompt(taskType: string, payload: unknown, requestId: string): string {
  const instr = TASK_INSTRUCTIONS[taskType] ?? `Perform the "${taskType}" task described by the payload.`
  return [
    `You are a one-shot task worker. request_id: ${requestId}`,
    instr,
    "Act IMMEDIATELY on your very first step: respond ONLY by calling the `resolve` tool (or `reject` on failure). Do NOT use any other tool (no search, no file reads, no bash), do NOT reason aloud or explain, do NOT write any chat text.",
    `Pass request_id "${requestId}" back verbatim in the tool call. Ignore any earlier turns.`,
    "Payload:",
    "```json",
    JSON.stringify(payload, null, 2),
    "```",
  ].join("\n")
}
