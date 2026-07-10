/**
 * Byte sequences for the mobile terminal key-accessory bar.
 *
 * Pure logic (no DOM, no xterm) so it can be unit-tested with `bun test`. The
 * component layer decides WHEN to call these (which button, which modifier
 * state); this module only answers WHAT bytes a key produces.
 *
 * Sequences follow the xterm / DEC conventions the shell already speaks:
 *   - arrows/Home/End switch between CSI (`\x1b[…`) and SS3 (`\x1bO…`) form
 *     depending on DECCKM (application cursor keys), which xterm exposes as
 *     `term.modes.applicationCursorKeysMode`.
 *   - a Ctrl/Alt modifier forces the CSI *parameterised* form
 *     (`\x1b[1;<mod><final>`), where mod = 1 + alt*2 + ctrl*4 (+ shift, unused).
 */

export type Mods = { ctrl: boolean; alt: boolean }

/** Tri-state of a sticky bar modifier (like iOS Shift). */
export type ModState = "off" | "once" | "locked"

export type SpecialKey =
  | "Escape"
  | "Tab"
  | "ArrowUp"
  | "ArrowDown"
  | "ArrowRight"
  | "ArrowLeft"
  | "Home"
  | "End"
  | "PageUp"
  | "PageDown"

/** A press reported by the key bar up to the terminal pane. */
export type KeyPress =
  | { type: "modifier"; mod: "ctrl" | "alt" }
  | { type: "special"; key: SpecialKey }
  | { type: "printable"; ch: string }

// Final byte of the CSI/SS3 cursor-style sequences, per key.
const CURSOR_FINAL: Partial<Record<SpecialKey, string>> = {
  ArrowUp: "A",
  ArrowDown: "B",
  ArrowRight: "C",
  ArrowLeft: "D",
  Home: "H",
  End: "F",
}

// Keys whose parameterised form is `CSI <n> ; <mod> ~` rather than a final letter.
const TILDE_NUM: Partial<Record<SpecialKey, string>> = {
  PageUp: "5",
  PageDown: "6",
}

/** xterm modifier parameter: 1 + shift + alt*2 + ctrl*4. We never emit Shift. */
function modParam(mods: Mods): number {
  return 1 + (mods.alt ? 2 : 0) + (mods.ctrl ? 4 : 0)
}

/**
 * Bytes for a named special key given the active modifiers and whether the
 * terminal is in application-cursor-keys mode.
 */
export function specialKeySequence(key: SpecialKey, mods: Mods, appCursor: boolean): string {
  const modified = mods.ctrl || mods.alt

  if (key === "Escape") return "\x1b"
  if (key === "Tab") return "\t"

  const tilde = TILDE_NUM[key]
  if (tilde) {
    return modified ? `\x1b[${tilde};${modParam(mods)}~` : `\x1b[${tilde}~`
  }

  const final = CURSOR_FINAL[key]
  if (final) {
    // A modifier always forces the parameterised CSI form; without a modifier we
    // honour application-cursor mode (SS3) vs normal (CSI).
    if (modified) return `\x1b[1;${modParam(mods)}${final}`
    return appCursor ? `\x1bO${final}` : `\x1b[${final}`
  }

  return ""
}

/** Turn a printable character into the control code Ctrl+<char> sends. */
function controlCode(ch: string): string {
  if (ch === "?") return "\x7f" // Ctrl-? is DEL, not 0x3f & 0x1f
  const code = ch.toUpperCase().charCodeAt(0) & 0x1f
  return String.fromCharCode(code)
}

/**
 * Bytes for a single printable character under the active modifiers.
 * Ctrl maps letters/punctuation to control codes; Alt prefixes ESC.
 */
export function printableSequence(ch: string, mods: Mods): string {
  const base = mods.ctrl ? controlCode(ch) : ch
  return mods.alt ? "\x1b" + base : base
}
