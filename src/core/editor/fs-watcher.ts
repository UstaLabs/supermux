import { watch, type FSWatcher } from "fs"
import { makeLogger } from "../../shared/log"

const log = makeLogger("fs-watcher")

const DEBOUNCE_MS = 500

// Paths matching these patterns are ignored
const IGNORE_PATTERNS = [
  /node_modules\//,
  /\.git\//,
  /\bdist\//,
  /\bbuild\//,
  /\b\.next\//,
  /\b\.nuxt\//,
  /\bout\//,
]

export type ChangeCallback = (paths: string[]) => void

interface WatcherEntry {
  fsWatcher: FSWatcher
  workdir: string
  subscribers: Set<ChangeCallback>
  // Debounce state
  pendingPaths: Set<string>
  debounceTimer: ReturnType<typeof setTimeout> | null
}

export class FsWatcher {
  private readonly entries = new Map<string, WatcherEntry>()

  /**
   * Subscribe to file changes for a session. If no watcher exists for the
   * session, one is created. Multiple subscribers share one watcher.
   */
  subscribe(sessionName: string, workdir: string, cb: ChangeCallback): void {
    let entry = this.entries.get(sessionName)

    if (!entry) {
      const fsWatcher = this.createFsWatcher(sessionName, workdir)
      entry = {
        fsWatcher,
        workdir,
        subscribers: new Set(),
        pendingPaths: new Set(),
        debounceTimer: null,
      }
      this.entries.set(sessionName, entry)
      log.info("fs_watch_started", { session: sessionName, workdir })
    }

    entry.subscribers.add(cb)
  }

  /**
   * Remove a callback. If subscriber count drops to 0, the watcher is closed.
   */
  unsubscribe(sessionName: string, cb: ChangeCallback): void {
    const entry = this.entries.get(sessionName)
    if (!entry) return

    entry.subscribers.delete(cb)

    if (entry.subscribers.size === 0) {
      this.closeEntry(sessionName, entry)
    }
  }

  /**
   * Force-close the watcher for a session regardless of subscriber count.
   */
  killSession(sessionName: string): void {
    const entry = this.entries.get(sessionName)
    if (!entry) return
    this.closeEntry(sessionName, entry)
  }

  /**
   * Return the number of active watchers.
   */
  activeCount(): number {
    return this.entries.size
  }

  /**
   * Close all active watchers.
   */
  shutdown(): void {
    for (const [sessionName, entry] of this.entries) {
      this.closeEntry(sessionName, entry)
    }
  }

  // ── private helpers ────────────────────────────────────────────────────────

  private createFsWatcher(sessionName: string, workdir: string): FSWatcher {
    const fsWatcher = watch(workdir, { recursive: true }, (eventType, filename) => {
      if (!filename) return

      // Normalise path separators
      const normalised = filename.replace(/\\/g, "/")

      // Check ignore patterns
      const checkPath = normalised.endsWith("/") ? normalised : normalised + "/"
      for (const pattern of IGNORE_PATTERNS) {
        if (pattern.test(checkPath) || pattern.test(normalised)) return
      }

      const entry = this.entries.get(sessionName)
      if (!entry) return

      entry.pendingPaths.add(normalised)

      // Reset debounce timer
      if (entry.debounceTimer !== null) {
        clearTimeout(entry.debounceTimer)
      }
      entry.debounceTimer = setTimeout(() => {
        const paths = Array.from(entry.pendingPaths)
        entry.pendingPaths.clear()
        entry.debounceTimer = null

        if (paths.length === 0) return

        for (const cb of entry.subscribers) {
          try {
            cb(paths)
          } catch (err) {
            log.warn("fs_watch_callback_error", {
              session: sessionName,
              error: String(err),
            })
          }
        }
      }, DEBOUNCE_MS)
    })

    fsWatcher.on("error", (err) => {
      log.error("fs_watch_error", { session: sessionName, error: String(err) })
    })

    return fsWatcher
  }

  private closeEntry(sessionName: string, entry: WatcherEntry): void {
    if (entry.debounceTimer !== null) {
      clearTimeout(entry.debounceTimer)
      entry.debounceTimer = null
    }
    try {
      entry.fsWatcher.close()
    } catch (err) {
      log.warn("fs_watch_close_error", { session: sessionName, error: String(err) })
    }
    this.entries.delete(sessionName)
    log.info("fs_watch_stopped", { session: sessionName })
  }
}
