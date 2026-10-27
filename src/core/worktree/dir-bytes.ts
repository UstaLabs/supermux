// Disk usage of a folder tree, in-process (replaces `du`, which Windows doesn't have).
// Async fs with a bounded number of calls in flight, so a big node_modules never blocks the
// event loop. Symlinks are not followed; a hardlinked file is counted once. Allocated blocks
// where the OS reports them (matches `du`), else the logical size (Windows).
import { lstat, readdir } from "fs/promises"
import { join } from "path"

export async function dirBytes(root: string, concurrency = 32): Promise<number | undefined> {
  const top = await lstat(root).catch(() => undefined)
  if (!top) return undefined
  let total = 0
  const seen = new Set<string>()
  const queue: string[] = [root]
  let active = 0
  return new Promise((done) => {
    const pump = () => {
      while (active < concurrency && queue.length) {
        const p = queue.pop()!
        active++
        lstat(p).then(async (st) => {
          if (st.nlink > 1 && st.ino && !st.isDirectory()) {
            const k = `${st.dev}:${st.ino}`
            if (seen.has(k)) return
            seen.add(k)
          }
          total += st.blocks ? st.blocks * 512 : st.size
          if (st.isDirectory()) for (const n of await readdir(p).catch(() => [] as string[])) queue.push(join(p, n))
        }, () => {}).finally(() => {
          active--
          if (!queue.length && !active) done(total)
          else pump()
        })
      }
    }
    pump()
  })
}
