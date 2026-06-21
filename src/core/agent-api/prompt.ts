// Shared, pure, tested voice-cleanup prompt builder. Moved verbatim out of
// voice-cleanup.ts so every adapter and the orchestrator build the same prompt.

export interface CleanupInput {
  draft: string
  recentMessages: { role: string; text: string }[]
  skills: string[]
  glossary: string[]
}

// One-shot prompt: must return ONLY the corrected text — no preamble, no tools.
export function buildCleanupPrompt(input: CleanupInput): string {
  const ctx = input.recentMessages.map((m) => `${m.role}: ${m.text}`).join("\n")
  return [
    "You clean up a rough speech-to-text draft into the user's intended message.",
    "Use the glossary, conversation context, and command/skill names below to fix mis-heard words — especially technical or product names that appear in the conversation.",
    "Preserve the user's meaning, wording, and tone. Do NOT answer the message, expand it, add content, explain yourself, or use any tools.",
    "Output ONLY the corrected text — nothing else.",
    input.glossary.length
      ? `\nKnown terms (use these EXACT spellings if a draft word sounds similar, even if a different similarly-named product exists): ${input.glossary.join(", ")}`
      : "",
    input.skills.length ? `\nKnown commands/skills: ${input.skills.join(", ")}` : "",
    input.recentMessages.length ? `\nConversation so far (most recent last):\n${ctx}` : "",
    `\nDraft: ${JSON.stringify(input.draft)}`,
    "\nCorrected text:",
  ]
    .filter(Boolean)
    .join("\n")
}
