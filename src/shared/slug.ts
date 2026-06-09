export function normalizeName(input: string): string {
  let n = input.toLowerCase()
  n = n.replace(/[^a-z0-9_-]+/g, "-")
  n = n.replace(/^-+|-+$/g, "")
  if (n.length > 24) n = n.slice(0, 24).replace(/-+$/, "")
  return n || "session"
}
