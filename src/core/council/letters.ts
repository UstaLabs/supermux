/** A member's fixed letter by position (spec §5): A–Z, then AA, AB, … — no member limit. */
export function letterAt(index: number): string {
  let n = index
  let out = ""
  do {
    out = String.fromCharCode(65 + (n % 26)) + out
    n = Math.floor(n / 26) - 1
  } while (n >= 0)
  return out
}
