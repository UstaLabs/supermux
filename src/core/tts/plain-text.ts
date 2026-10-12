/**
 * Flatten markdown-ish agent text for TTS.
 * Mirrors web/Android/iOS client helpers so server chunking matches what clients speak.
 */
export function plainTextForSpeech(md: string): string {
  if (!md) return ""
  let s = md
  s = s.replace(/```[\s\S]*?```/g, " ")
  s = s.replace(/`([^`]+)`/g, "$1")
  s = s.replace(/!\[([^\]]*)\]\([^)]*\)/g, "$1")
  s = s.replace(/\[([^\]]+)\]\([^)]*\)/g, "$1")
  s = s.replace(/^#{1,6}\s+/gm, "")
  s = s.replace(/^\s*[-*+]\s+/gm, "")
  s = s.replace(/^\s*\d+\.\s+/gm, "")
  s = s.replace(/^\s*>\s?/gm, "")
  s = s.replace(/(\*\*|__)(.*?)\1/g, "$2")
  s = s.replace(/(\*|_)(.*?)\1/g, "$2")
  s = s.replace(/~~(.*?)~~/g, "$1")
  s = s.replace(/\n{2,}/g, ". ")
  s = s.replace(/\n/g, " ")
  s = s.replace(/\s+/g, " ").trim()
  s = s.replace(/(?:\.\s*){2,}/g, ". ").replace(/\s+/g, " ").trim()
  return s
}

/**
 * Split plain text into chunks of at most `maxChars`, preferring sentence boundaries.
 *
 * `rampChars` caps the first few pieces tighter than `maxChars`: playback can only start once the
 * first piece is synthesized, so a short opening sentence starts the voice in ~1s while the bigger
 * pieces behind it synthesize during playback. Later pieces fall back to `maxChars`.
 */
export function splitForTts(text: string, maxChars: number, rampChars: readonly number[] = []): string[] {
  const t = text.trim()
  if (!t) return []

  const out: string[] = []
  let rest = t
  while (rest) {
    const budget = Math.min(rampChars[out.length] ?? maxChars, maxChars)
    if (rest.length <= budget) {
      out.push(rest)
      break
    }
    const cut = findCut(rest, budget, maxChars)
    const piece = rest.slice(0, cut).trim()
    if (piece) out.push(piece)
    rest = rest.slice(cut).trim()
  }
  return out
}

// End of a sentence: terminal punctuation (plus closing quotes/brackets) followed by whitespace.
const SENTENCE_END = /[.!?…]["'”’)\]]*\s/g
const CLAUSE_END = /[,;:—]\s/g

/** Index just past the last `re` match ending at or before `limit` (0 if none). */
function lastEnd(s: string, re: RegExp, limit: number): number {
  let best = 0
  re.lastIndex = 0
  for (let m = re.exec(s); m && m.index + m[0].length <= limit + 1; m = re.exec(s)) {
    best = m.index + m[0].length - 1 // keep the punctuation, drop the space
  }
  return best
}

/** Index just past the first `re` match ending after `from` and at or before `limit` (0 if none). */
function firstEnd(s: string, re: RegExp, from: number, limit: number): number {
  re.lastIndex = 0
  for (let m = re.exec(s); m; m = re.exec(s)) {
    const end = m.index + m[0].length - 1
    if (end > limit) return 0
    if (end > from) return end
  }
  return 0
}

/**
 * Where to cut `s` for a piece of roughly `budget` chars. In order: the last sentence end in the
 * budget's back 60%; else the next sentence end within twice the budget (a whole sentence reads
 * better than a word cut, and a ramped budget may overshoot); else a clause boundary; else a word;
 * else a hard cut.
 */
function findCut(s: string, budget: number, maxChars: number): number {
  const floor = budget * 0.4
  const sentence = lastEnd(s, SENTENCE_END, budget)
  if (sentence >= floor) return sentence
  const next = firstEnd(s, SENTENCE_END, budget, Math.min(budget * 2, maxChars))
  if (next) return next
  const clause = lastEnd(s, CLAUSE_END, budget)
  if (clause >= floor) return clause
  const space = s.lastIndexOf(" ", budget)
  if (space >= budget * 0.3) return space
  return budget
}
