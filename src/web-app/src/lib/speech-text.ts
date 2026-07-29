/**
 * Flatten markdown-ish agent text into something tolerable for TTS.
 * Strips fences/links/emphasis; keeps prose order.
 */
export function plainTextForSpeech(md: string): string {
  if (!md) return ""
  let s = md
  // Fenced code → short pause marker (avoid reading code raw)
  s = s.replace(/```[\s\S]*?```/g, " ")
  // Inline code
  s = s.replace(/`([^`]+)`/g, "$1")
  // Images / links keep alt or label
  s = s.replace(/!\[([^\]]*)\]\([^)]*\)/g, "$1")
  s = s.replace(/\[([^\]]+)\]\([^)]*\)/g, "$1")
  // Headings / list markers / blockquotes
  s = s.replace(/^#{1,6}\s+/gm, "")
  s = s.replace(/^\s*[-*+]\s+/gm, "")
  s = s.replace(/^\s*\d+\.\s+/gm, "")
  s = s.replace(/^\s*>\s?/gm, "")
  // Emphasis / strikethrough
  s = s.replace(/(\*\*|__)(.*?)\1/g, "$2")
  s = s.replace(/(\*|_)(.*?)\1/g, "$2")
  s = s.replace(/~~(.*?)~~/g, "$1")
  // Collapse whitespace; turn blank lines into a short pause via period
  s = s.replace(/\n{2,}/g, ". ")
  s = s.replace(/\n/g, " ")
  s = s.replace(/\s+/g, " ").trim()
  // Clean up artifacts from stripped fences ("Hello. . world")
  s = s.replace(/(?:\.\s*){2,}/g, ". ").replace(/\s+/g, " ").trim()
  return s
}
