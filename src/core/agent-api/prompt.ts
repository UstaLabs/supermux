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
    "You rewrite a rough speech-to-text draft into a clear, well-phrased message that says what the user meant.",
    "Fix mis-heard words (use the glossary + conversation context below for technical/product names), correct grammar and punctuation, remove speech disfluencies (um, uh, \"like\", false starts, repeated words), and tighten rambling or run-on phrasing.",
    "You MAY restructure sentences for clarity, flow, and coherence with the conversation — make it read like a polished written message, not a raw transcript.",
    "PRESERVE the user's meaning, intent, and tone. Do NOT answer the message, add information they didn't say, change what they're asking for, explain yourself, or use any tools.",
    "Output ONLY the improved message — nothing else.",
    input.glossary.length
      ? `\nKnown terms (use these EXACT spellings if a draft word sounds similar, even if a different similarly-named product exists): ${input.glossary.join(", ")}`
      : "",
    input.skills.length ? `\nKnown commands/skills: ${input.skills.join(", ")}` : "",
    input.recentMessages.length ? `\nConversation so far (most recent last):\n${ctx}` : "",
    `\nDraft: ${JSON.stringify(input.draft)}`,
    "\nImproved message:",
  ]
    .filter(Boolean)
    .join("\n")
}
