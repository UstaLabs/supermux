import { test, expect } from "bun:test"
import { specialKeySequence, printableSequence, type Mods } from "./terminal-keys"

const NONE: Mods = { ctrl: false, alt: false }
const CTRL: Mods = { ctrl: true, alt: false }
const ALT: Mods = { ctrl: false, alt: true }
const BOTH: Mods = { ctrl: true, alt: true }

// --- Unmodified special keys, normal (non-application) cursor mode ---
test("arrows send CSI sequences in normal cursor mode", () => {
  expect(specialKeySequence("ArrowUp", NONE, false)).toBe("\x1b[A")
  expect(specialKeySequence("ArrowDown", NONE, false)).toBe("\x1b[B")
  expect(specialKeySequence("ArrowRight", NONE, false)).toBe("\x1b[C")
  expect(specialKeySequence("ArrowLeft", NONE, false)).toBe("\x1b[D")
})

// --- Application cursor keys mode (DECCKM) — vim/tmux full-screen apps ---
test("arrows send SS3 sequences in application cursor mode", () => {
  expect(specialKeySequence("ArrowUp", NONE, true)).toBe("\x1bOA")
  expect(specialKeySequence("ArrowDown", NONE, true)).toBe("\x1bOB")
  expect(specialKeySequence("ArrowRight", NONE, true)).toBe("\x1bOC")
  expect(specialKeySequence("ArrowLeft", NONE, true)).toBe("\x1bOD")
})

test("Home and End follow cursor-keys mode", () => {
  expect(specialKeySequence("Home", NONE, false)).toBe("\x1b[H")
  expect(specialKeySequence("End", NONE, false)).toBe("\x1b[F")
  expect(specialKeySequence("Home", NONE, true)).toBe("\x1bOH")
  expect(specialKeySequence("End", NONE, true)).toBe("\x1bOF")
})

test("Esc, Tab, PageUp, PageDown are fixed regardless of mode", () => {
  expect(specialKeySequence("Escape", NONE, false)).toBe("\x1b")
  expect(specialKeySequence("Tab", NONE, false)).toBe("\t")
  expect(specialKeySequence("PageUp", NONE, false)).toBe("\x1b[5~")
  expect(specialKeySequence("PageDown", NONE, false)).toBe("\x1b[6~")
  // app mode does not change these
  expect(specialKeySequence("PageUp", NONE, true)).toBe("\x1b[5~")
})

// --- Modified special keys use CSI parameterised form (modifier = 1 + alt*2 + ctrl*4) ---
test("modified arrows use CSI 1;<mod> form, overriding application mode", () => {
  expect(specialKeySequence("ArrowUp", CTRL, false)).toBe("\x1b[1;5A")
  expect(specialKeySequence("ArrowLeft", ALT, false)).toBe("\x1b[1;3D")
  expect(specialKeySequence("ArrowRight", BOTH, false)).toBe("\x1b[1;7C")
  // even in application cursor mode, a modifier forces the CSI form
  expect(specialKeySequence("ArrowUp", CTRL, true)).toBe("\x1b[1;5A")
})

test("modified Home/End use CSI 1;<mod> form", () => {
  expect(specialKeySequence("Home", CTRL, false)).toBe("\x1b[1;5H")
  expect(specialKeySequence("End", ALT, false)).toBe("\x1b[1;3F")
})

test("modified PageUp/PageDown use CSI <n>;<mod>~ form", () => {
  expect(specialKeySequence("PageUp", CTRL, false)).toBe("\x1b[5;5~")
  expect(specialKeySequence("PageDown", ALT, false)).toBe("\x1b[6;3~")
})

// --- Printable characters ---
test("plain printable char is unchanged", () => {
  expect(printableSequence("|", NONE)).toBe("|")
  expect(printableSequence("a", NONE)).toBe("a")
})

test("Ctrl + letter produces the control code", () => {
  expect(printableSequence("c", CTRL)).toBe("\x03") // Ctrl-C
  expect(printableSequence("C", CTRL)).toBe("\x03") // case-insensitive
  expect(printableSequence("d", CTRL)).toBe("\x04") // Ctrl-D (EOF)
  expect(printableSequence("a", CTRL)).toBe("\x01") // Ctrl-A (tmux prefix / bol)
  expect(printableSequence("z", CTRL)).toBe("\x1a") // Ctrl-Z (suspend)
})

test("Ctrl + punctuation produces the classic control codes", () => {
  expect(printableSequence("[", CTRL)).toBe("\x1b") // Ctrl-[ == Esc
  expect(printableSequence("\\", CTRL)).toBe("\x1c")
  expect(printableSequence("]", CTRL)).toBe("\x1d")
  expect(printableSequence("_", CTRL)).toBe("\x1f")
  expect(printableSequence("?", CTRL)).toBe("\x7f") // Ctrl-? == DEL
  expect(printableSequence(" ", CTRL)).toBe("\x00") // Ctrl-Space == NUL
})

test("Alt + printable prefixes ESC", () => {
  expect(printableSequence("b", ALT)).toBe("\x1bb") // Alt-b == word back in readline
  expect(printableSequence("|", ALT)).toBe("\x1b|")
})

test("Ctrl+Alt + letter is ESC followed by the control code", () => {
  expect(printableSequence("c", BOTH)).toBe("\x1b\x03")
})
