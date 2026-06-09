export function clip(s: string, max: number): { text: string; truncated: boolean } {
  if (s.length <= max) return { text: s, truncated: false }
  return { text: s.slice(0, max - 1) + "…", truncated: true }
}

export function firstLine(s: string): string {
  for (const ln of s.split("\n")) { const t = ln.trim(); if (t) return t }
  return s.trim()
}

export function pickString(obj: unknown, fields: string[]): string {
  if (!obj || typeof obj !== "object" || Array.isArray(obj)) return ""
  const rec = obj as Record<string, unknown>
  for (const f of fields) {
    const v = rec[f]
    if (typeof v === "string" && v) return v
    if (Array.isArray(v) && v.every((x) => typeof x === "string") && v.length) return (v as string[]).join(" ")
  }
  const firstStr = Object.values(rec).find((v) => typeof v === "string" && v) as string | undefined
  return firstStr ?? ""
}
