export async function waitForRegisteredSession<T>(opts: {
  id: string
  name: string
  lookup: (id: string, name: string) => T | undefined
  timeoutMs?: number
  intervalMs?: number
}): Promise<T> {
  const timeoutMs = opts.timeoutMs ?? 10_000
  const intervalMs = opts.intervalMs ?? 50
  const deadline = Date.now() + timeoutMs

  while (Date.now() <= deadline) {
    const found = opts.lookup(opts.id, opts.name)
    if (found) return found
    await new Promise((resolve) => setTimeout(resolve, intervalMs))
  }

  throw new Error(`timed out waiting for session "${opts.name}" to register`)
}
