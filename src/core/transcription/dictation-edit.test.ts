import { expect, test } from "bun:test"
import { checkDictationEdit, editIsGrounded, parseEditReply } from "./dictation-edit"
import type { AgentApi } from "../agent-api/types"

const api = (reply: string, seen?: { prompt?: string; opts?: any }): AgentApi => ({
  name: "codex",
  isAvailable: () => true,
  complete: async (prompt, opts) => { if (seen) { seen.prompt = prompt; seen.opts = opts }; return reply },
})

test("parseEditReply", () => {
  expect(parseEditReply('{"kind":"dictation"}')).toEqual({ kind: "dictation" })
  expect(parseEditReply('```json\n{"kind":"edit","text":" A. "}\n```')).toEqual({ kind: "edit", text: "A." })
  expect(parseEditReply("sure!")).toBeNull()
})

test("grounding: deletions and words from the spoken edit are fine; invented words are not", () => {
  expect(editIsGrounded("Meet on Monday. Bring snacks.", "No wait, make it Tuesday and remove the last sentence.", "Meet on Tuesday.")).toBe(true)
  expect(editIsGrounded("Meet on Monday.", "make it Tuesday", "Meet on Tuesday at noon.")).toBe(false)
})

test("an edit comes back as the edited text, asked of Luna with reasoning off", async () => {
  const seen: { prompt?: string; opts?: any } = {}
  const r = await checkDictationEdit(
    { text: "Meet on Monday. Bring snacks.", newWords: "Remove the last sentence." },
    api('{"kind":"edit","text":"Meet on Monday."}', seen),
  )
  expect(r.text).toBe("Meet on Monday.")
  expect(seen.opts.model).toBe("gpt-6-luna")
  expect(seen.opts.reasoningEffort).toBe("none")
  expect(seen.prompt).toContain("NEW WORDS: Remove the last sentence.")
})

test("dictation, an ungrounded edit, and empty input all answer null", async () => {
  expect((await checkDictationEdit({ text: "a b", newWords: "c d" }, api('{"kind":"dictation"}'))).text).toBeNull()
  expect((await checkDictationEdit({ text: "Meet on Monday.", newWords: "make it Tuesday" }, api('{"kind":"edit","text":"Meet on Tuesday at noon."}'))).text).toBeNull()
  expect((await checkDictationEdit({ text: "", newWords: "scratch that" }, api('{"kind":"edit","text":""}'))).text).toBeNull()
})
