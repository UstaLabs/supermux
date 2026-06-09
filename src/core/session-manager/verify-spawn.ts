// tmux's new-window can exit 0 and the window can still die instantly
// (claude crashes on startup, workdir is bad, etc.). Wait a beat and
// confirm the window is still around.

export async function verifySpawnSurvived(opts: {
  name: string
  listWindows: () => Promise<string[]>
  waitMs?: number
}): Promise<boolean> {
  await new Promise((resolve) => setTimeout(resolve, opts.waitMs ?? 1500))
  const windows = await opts.listWindows()
  return windows.includes(opts.name)
}
