export interface DefaultProjectInputs {
  /** The currently-selected working directory. */
  current: string
  /** Project working directories, most-recently-active first. */
  recent: string[]
  /** The user explicitly chose a path via the project picker. */
  picked: boolean
  /** The user has started composing — typing, attaching a file, or recording. */
  composing: boolean
}

/**
 * The working directory the launcher should show.
 *
 * Before the user engages with the screen we follow the most-recently-used
 * project, so the default settles to something sensible as session/message data
 * hydrates after mount. Once the user has engaged — picked a path or started
 * composing — the selection is frozen: a message arriving in another session
 * reshuffles the recency order, and we must never swap the project out from
 * under someone who is mid-compose (they'd send their prompt to the wrong one).
 */
export function chooseDefaultProject({ current, recent, picked, composing }: DefaultProjectInputs): string {
  if (picked || composing) return current
  return recent[0] ?? current
}
