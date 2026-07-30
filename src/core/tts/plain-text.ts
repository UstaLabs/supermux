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
 */
export function splitForTts(text: string, maxChars: number): string[] {
  const t = text.trim()
  if (!t) return []
  if (t.length <= maxChars) return [t]

  const out: string[] = []
  let rest = t
  while (rest.length > maxChars) {
    let cut = rest.lastIndexOf(". ", maxChars)
    if (cut < maxChars * 0.4) cut = rest.lastIndexOf(" ", maxChars)
    if (cut < maxChars * 0.3) cut = maxChars
    // include trailing period when we split on ". "
    if (rest[cut] === ".") cut += 1
    const piece = rest.slice(0, cut).trim()
    if (piece) out.push(piece)
    rest = rest.slice(cut).trim()
  }
  if (rest) out.push(rest)
  return out
}
