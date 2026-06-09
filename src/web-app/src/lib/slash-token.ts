/** Active `/command` token at the cursor (must start at BOL or after whitespace). */
export interface ActiveSlashToken {
  start: number
  query: string
}

export function activeSlashToken(text: string, cursor: number): ActiveSlashToken | null {
  const before = text.slice(0, Math.max(0, cursor))
  const match = before.match(/(?:^|\s)(\/[^\s]*)$/)
  if (!match?.[1]) return null
  const token = match[1]
  return { start: before.length - token.length, query: token.slice(1).toLowerCase() }
}
