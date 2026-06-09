// Human-readable elapsed duration: 5 -> "5 seconds", 185 -> "3 minutes 5 seconds",
// 3725 -> "1 hour 2 minutes", 90000 -> "1 day 1 hour". Shows the two largest
// relevant units (seconds only while under an hour).
export function formatDuration(totalSeconds: number): string {
  const s = Math.max(0, Math.floor(totalSeconds))
  const days = Math.floor(s / 86400)
  const hours = Math.floor((s % 86400) / 3600)
  const minutes = Math.floor((s % 3600) / 60)
  const seconds = s % 60
  const u = (n: number, unit: string) => `${n} ${unit}${n === 1 ? "" : "s"}`
  if (days > 0) return hours > 0 ? `${u(days, "day")} ${u(hours, "hour")}` : u(days, "day")
  if (hours > 0) return minutes > 0 ? `${u(hours, "hour")} ${u(minutes, "minute")}` : u(hours, "hour")
  if (minutes > 0) return seconds > 0 ? `${u(minutes, "minute")} ${u(seconds, "second")}` : u(minutes, "minute")
  return u(seconds, "second")
}
