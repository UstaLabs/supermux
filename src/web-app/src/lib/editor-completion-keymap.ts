import { startCompletion } from "@codemirror/autocomplete"
import { Prec } from "@codemirror/state"
import { keymap } from "@codemirror/view"

/**
 * Mac-friendly completion triggers. CodeMirror's default completionKeymap uses
 * Ctrl-Space (Spotlight / IME on macOS) and Alt-i / Alt-` — not Alt-Space.
 */
export const editorCompletionKeymap = Prec.highest(keymap.of([
  { key: "Alt-Space", run: startCompletion },
  { mac: "Cmd-Shift-Space", run: startCompletion },
  { mac: "F12", run: startCompletion },
]))
