// Maps a Codex rate-limit-reset redemption code to a short user-facing note.
// Kept pure + outside the Vue component so it's unit-testable.
export function codexResetNote(code: string, windowsReset: number): string {
  switch (code) {
    case "reset":
      return `✓ Reset — cleared ${windowsReset} window${windowsReset === 1 ? "" : "s"}`
    case "nothing_to_reset":
      return "Nothing to reset right now — your windows aren't capped"
    case "no_credit":
      return "No banked resets left"
    case "already_redeemed":
      return "That reset was already redeemed"
    default:
      return "Reset request completed"
  }
}
