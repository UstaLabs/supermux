// Spoken edits during dictation ("remove the last sentence", "no wait, make it Tuesday",
// "son cümleyi sil"). After a live pass appends the newly spoken words, the client asks here
// whether those words were an edit of the text rather than more of it; if so, the answer is the
// edited text.
//
// One GPT-6 Luna call over TEXT ONLY (no audio, so nothing to hallucinate from), reasoning off:
// measured 2026-10-11 at 16/16 on a mixed English/Turkish set, ~1–2 s, including the hard cases —
// instructions dictated TO A CODING AGENT ("remove the debug logging you added", "rengi maviye
// değiştir") are dictation, not edits. A local decision model (Julia-1, 144M) scored 11/16 there
// with 84–99%-confident misses, so it is not used.

import { makeLogger } from "../../shared/log"
import type { AgentApi } from "../agent-api/types"

const log = makeLogger("dictation-edit")

export const EDIT_MODEL = "gpt-6-luna"

export const EDIT_INSTRUCTIONS =
  "You handle one step of a voice dictation. TEXT is what is already written; NEW WORDS were just spoken and are NOT part of TEXT.\n" +
  "\"edit\" = the NEW WORDS tell the dictation to change TEXT itself (delete, replace or correct words already written), e.g. " +
  "\"remove the last sentence\", \"no wait, make it Tuesday\", \"scratch that\", \"son cümleyi sil\".\n" +
  "\"dictation\" = the NEW WORDS are more content for the message. The user often dictates instructions to a coding agent " +
  "(\"delete the unused imports\", \"change the button color to blue\", \"remove the debug logging\") — those are dictation, " +
  "because they talk about code or the world, not about TEXT.\n" +
  "For an edit, also give the edited TEXT: apply the instruction and nothing else. Do not add, rephrase or translate anything; " +
  "new words may only come from the NEW WORDS (e.g. \"Tuesday\").\n" +
  "Reply with JSON only: {\"kind\":\"dictation\"} or {\"kind\":\"edit\",\"text\":\"<edited TEXT>\"}"

const norm = (w: string) => w.toLowerCase().replace(/[^\p{L}\p{N}]/gu, "")
const words = (s: string) => s.split(/\s+/).map(norm).filter(Boolean)

/**
 * The anti-invention guard: an edit may delete, reorder and replace words, but every word in its
 * result must already be in TEXT or in what the user just said.
 */
export function editIsGrounded(text: string, newWords: string, edited: string): boolean {
  const allowed = new Set([...words(text), ...words(newWords)])
  return words(edited).every((w) => allowed.has(w))
}

/** The model's reply → the edited text, or null for "not an edit" / anything unusable. */
export function parseEditReply(raw: string): { kind: "edit"; text: string } | { kind: "dictation" } | null {
  const json = raw.trim().replace(/^```(?:json)?\s*|\s*```$/g, "")
  try {
    const v = JSON.parse(json)
    if (v?.kind === "dictation") return { kind: "dictation" }
    if (v?.kind === "edit" && typeof v.text === "string") return { kind: "edit", text: v.text.trim() }
  } catch { /* not JSON */ }
  return null
}

export interface EditResult {
  /** The edited text; null = the new words are dictation (or the check failed — same answer). */
  text: string | null
}

export async function checkDictationEdit(
  input: { text: string; newWords: string },
  api: AgentApi,
): Promise<EditResult> {
  const text = input.text.trim()
  const newWords = input.newWords.trim()
  if (!text || !newWords) return { text: null }
  const t0 = Date.now()
  const raw = await api.complete(`TEXT: ${text}\nNEW WORDS: ${newWords}`, {
    model: EDIT_MODEL,
    instructions: EDIT_INSTRUCTIONS,
    reasoningEffort: "none",
    timeoutMs: 8_000,
  })
  const reply = parseEditReply(raw)
  const ms = Date.now() - t0
  if (reply?.kind !== "edit") {
    log.info("dictation_edit_check", { ms, kind: reply?.kind ?? "unparsed" })
    return { text: null }
  }
  if (!editIsGrounded(text, newWords, reply.text)) {
    log.warn("dictation_edit_ungrounded", { ms, text, newWords, edited: reply.text })
    return { text: null }
  }
  log.info("dictation_edit_applied", { ms, before: text.length, after: reply.text.length })
  return { text: reply.text }
}
