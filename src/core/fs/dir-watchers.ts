import { watch as fsWatch, type FSWatcher } from "fs"
import { basename } from "path"

export type WatchListener = (event: string, filename?: string | null) => void

export interface DirWatchersOpts {
  debounceMs?: number
  maxWaitMs?: number
  pollMs?: number
  watchFn?: (dir: string, listener: WatchListener) => FSWatcher
  onFallback?: (dir: string) => void
  /** Only events whose file name passes are acted on. Events without a name always pass. */
  filter?: (dir: string, filename: string) => boolean
}

interface Slot {
  watcher?: FSWatcher
  poll?: ReturnType<typeof setInterval>
  timer?: ReturnType<typeof setTimeout>
  firstAt?: number
  /** The watch can no longer be trusted (the folder itself was removed/replaced, or the watch errored). */
  dead?: boolean
}

const defaultWatch = (dir: string, listener: WatchListener) =>
  fsWatch(dir, { persistent: false }, (event, filename) => listener(event, filename == null ? filename : String(filename)))

/** One NON-recursive watch per folder. Events are debounced into `onFlush(dir)`. */
export class DirWatchers {
  private readonly slots = new Map<string, Slot>()
  private readonly debounceMs: number
  private readonly maxWaitMs: number
  private readonly pollMs: number
  private readonly watchFn: NonNullable<DirWatchersOpts["watchFn"]>

  constructor(private readonly onFlush: (dir: string) => void, private readonly opts: DirWatchersOpts = {}) {
    this.debounceMs = opts.debounceMs ?? 100
    this.maxWaitMs = opts.maxWaitMs ?? 1_000
    this.pollMs = opts.pollMs ?? 5_000
    this.watchFn = opts.watchFn ?? defaultWatch
  }

  get size(): number { return this.slots.size }
  has(dir: string): boolean { return this.slots.has(dir) }
  /** True once the watch on `dir` stopped being reliable; `unwatch` + `watch` gives a fresh one. */
  isDead(dir: string): boolean { return this.slots.get(dir)?.dead === true }

  watch(dir: string): void {
    if (this.slots.has(dir)) return
    const slot: Slot = {}
    this.slots.set(dir, slot)
    try {
      slot.watcher = this.watchFn(dir, (event, filename) => this.onEvent(dir, slot, event, filename))
      slot.watcher.on?.("error", () => {
        // The folder vanished or the watch broke: flush (the reload reports gone) and stop.
        try { slot.watcher?.close() } catch {}
        slot.watcher = undefined
        slot.dead = true
        this.schedule(dir)
      })
    } catch {
      this.opts.onFallback?.(dir)
      slot.poll = setInterval(() => this.flush(dir), this.pollMs)
    }
  }

  unwatch(dir: string): void {
    const slot = this.slots.get(dir)
    if (!slot) return
    this.slots.delete(dir)
    if (slot.timer) clearTimeout(slot.timer)
    if (slot.poll) clearInterval(slot.poll)
    try { slot.watcher?.close() } catch {}
  }

  closeAll(): void {
    for (const dir of [...this.slots.keys()]) this.unwatch(dir)
  }

  private onEvent(dir: string, slot: Slot, event: string, filename: string | null | undefined): void {
    if (this.slots.get(dir) !== slot) return
    // No name (Bun reports the watched folder's own deletion as `rename` + undefined), or the folder's
    // own name: the watch is on an inode that may be gone. Inode numbers get reused (ext4), so this
    // flag is the reliable signal for "re-watch".
    if (!filename || (event === "rename" && filename === basename(dir))) slot.dead = true
    else if (this.opts.filter && !this.opts.filter(dir, filename)) return
    this.schedule(dir)
  }

  /** A throwing consumer must never take down the timers of every other folder. */
  private flush(dir: string): void {
    try {
      this.onFlush(dir)
    } catch (e) {
      console.error("[dir-watchers] flush failed", dir, e)
    }
  }

  private schedule(dir: string): void {
    const slot = this.slots.get(dir)
    if (!slot) return
    const now = Date.now()
    slot.firstAt ??= now
    if (slot.timer) clearTimeout(slot.timer)
    const wait = Math.max(0, Math.min(this.debounceMs, this.maxWaitMs - (now - slot.firstAt)))
    slot.timer = setTimeout(() => {
      slot.timer = undefined
      slot.firstAt = undefined
      if (this.slots.get(dir) === slot) this.flush(dir)
    }, wait)
  }
}
