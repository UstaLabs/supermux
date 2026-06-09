/** True when the event target is a text field the user is typing in. */
export function isEditableTarget(target: EventTarget | null): boolean {
  if (!target || !(target instanceof HTMLElement)) return false
  const tag = target.tagName
  if (tag === "INPUT" || tag === "TEXTAREA") return true
  if (target.isContentEditable) return true
  return !!target.closest("[contenteditable='true']")
}

/** True when a pane owns keyboard input (terminal, scrcpy, etc.). */
export function isPaneKeyboardTarget(target: EventTarget | null): boolean {
  if (!target || !(target instanceof HTMLElement)) return false
  if (target.closest(".xterm")) return true
  if (target.closest("[data-cmux-keyboard-owner]")) return true
  return false
}
