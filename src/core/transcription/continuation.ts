// Joining a live dictation pass onto the text so far. The pass's audio starts ~400 ms before the
// previous pass ended (so a word cut there is heard whole), which means its transcript can begin
// with the last word or two of the text again. Those are dropped here; the text so far is kept
// verbatim (it may hold the user's own edits).

const norm = (w: string) => w.toLowerCase().replace(/[^\p{L}\p{N}]/gu, "")

/** Longest overlap looked for — 400 ms of audio is a word or two, rarely more. */
const MAX_OVERLAP_WORDS = 4

/**
 * [previous] continued by [next]. A one-word overlap only counts for a word of 4+ letters: short
 * words repeat for real ("it it", "ve ve") far more often than a long one does.
 */
export function joinContinuation(previous: string, next: string): string {
  const prev = previous.trim()
  const nxt = next.trim()
  if (!prev) return nxt
  if (!nxt) return prev
  const pw = prev.split(/\s+/).map(norm)
  const nRaw = nxt.split(/\s+/)
  const nw = nRaw.map(norm)
  for (let k = Math.min(MAX_OVERLAP_WORDS, pw.length, nw.length); k >= 1; k--) {
    const tail = pw.slice(pw.length - k)
    const head = nw.slice(0, k)
    if (tail.some((w) => !w) || tail.join(" ") !== head.join(" ")) continue
    if (k === 1 && tail[0]!.length < 4) continue
    const rest = nRaw.slice(k).join(" ")
    return rest ? `${prev} ${rest}` : prev
  }
  return `${prev} ${nxt}`
}
