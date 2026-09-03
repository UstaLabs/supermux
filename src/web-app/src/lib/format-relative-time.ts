// Relative "as of" label for a snapshot timestamp. Under a minute → "just now";
// otherwise "as of 2m ago" / "as of 3h ago" / "as of 1d ago".
export function formatAsOf(iso: string | null | undefined, now = Date.now()): string {
  if (!iso) return ""
  const ms = Date.parse(iso)
  if (!Number.isFinite(ms)) return ""
  const diff = Math.max(0, now - ms)
  if (diff < 60_000) return "just now"
  const minutes = Math.floor(diff / 60_000)
  if (minutes < 60) return `as of ${minutes}m ago`
  const hours = Math.floor(minutes / 60)
  if (hours < 24) return `as of ${hours}h ago`
  return `as of ${Math.floor(hours / 24)}d ago`
}
