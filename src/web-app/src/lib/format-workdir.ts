import { inferHomeDir as inferHomeDirFromDisplay, workdirDisplay } from "./workdir-display"

/**
 * Format a workdir for display: ~/… when under home, otherwise shortened absolute.
 */
export function formatWorkdir(workdir: string, homeDir?: string | null): string {
  return workdirDisplay(workdir, homeDir).label
}

/** Best-effort home dir when the server hasn't sent one yet. */
export function inferHomeDir(workdir?: string): string | null {
  return inferHomeDirFromDisplay(workdir)
}
