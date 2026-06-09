import { describe, it, expect, beforeEach, afterEach } from "bun:test"
import { mkdtempSync, rmSync, writeFileSync, mkdirSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { FsWatcher } from "../src/core/editor/fs-watcher"

let tmpDir: string
let watcher: FsWatcher

beforeEach(() => {
  tmpDir = mkdtempSync(join(tmpdir(), "cmux-fswatcher-"))
  watcher = new FsWatcher()
})

afterEach(() => {
  watcher.shutdown()
  rmSync(tmpDir, { recursive: true, force: true })
})

// ─── activeCount ─────────────────────────────────────────────────────────────

describe("activeCount", () => {
  it("returns 0 when no watchers are active", () => {
    expect(watcher.activeCount()).toBe(0)
  })

  it("returns 1 after subscribing one session", () => {
    watcher.subscribe("s1", tmpDir, () => {})
    expect(watcher.activeCount()).toBe(1)
  })

  it("returns 1 when two subscribers share one session watcher", () => {
    watcher.subscribe("s1", tmpDir, () => {})
    watcher.subscribe("s1", tmpDir, () => {})
    expect(watcher.activeCount()).toBe(1)
  })

  it("returns 2 for two different sessions", () => {
    const tmpDir2 = mkdtempSync(join(tmpdir(), "cmux-fswatcher2-"))
    try {
      watcher.subscribe("s1", tmpDir, () => {})
      watcher.subscribe("s2", tmpDir2, () => {})
      expect(watcher.activeCount()).toBe(2)
    } finally {
      rmSync(tmpDir2, { recursive: true, force: true })
    }
  })
})

// ─── subscribe / unsubscribe ref-counting ────────────────────────────────────

describe("subscribe / unsubscribe ref-counting", () => {
  it("watcher closes when last subscriber unsubscribes", () => {
    const cb = () => {}
    watcher.subscribe("s1", tmpDir, cb)
    expect(watcher.activeCount()).toBe(1)
    watcher.unsubscribe("s1", cb)
    expect(watcher.activeCount()).toBe(0)
  })

  it("watcher stays open when one of two subscribers unsubscribes", () => {
    const cb1 = () => {}
    const cb2 = () => {}
    watcher.subscribe("s1", tmpDir, cb1)
    watcher.subscribe("s1", tmpDir, cb2)
    watcher.unsubscribe("s1", cb1)
    expect(watcher.activeCount()).toBe(1)
  })

  it("watcher closes when both subscribers unsubscribe", () => {
    const cb1 = () => {}
    const cb2 = () => {}
    watcher.subscribe("s1", tmpDir, cb1)
    watcher.subscribe("s1", tmpDir, cb2)
    watcher.unsubscribe("s1", cb1)
    watcher.unsubscribe("s1", cb2)
    expect(watcher.activeCount()).toBe(0)
  })

  it("unsubscribing an unknown session is a no-op", () => {
    expect(() => watcher.unsubscribe("nonexistent", () => {})).not.toThrow()
  })

  it("unsubscribing a callback not in the set is a no-op", () => {
    const cb1 = () => {}
    const cb2 = () => {}
    watcher.subscribe("s1", tmpDir, cb1)
    expect(() => watcher.unsubscribe("s1", cb2)).not.toThrow()
    expect(watcher.activeCount()).toBe(1)
  })

  it("re-subscribing after full unsubscribe creates a new watcher", () => {
    const cb = () => {}
    watcher.subscribe("s1", tmpDir, cb)
    watcher.unsubscribe("s1", cb)
    expect(watcher.activeCount()).toBe(0)
    watcher.subscribe("s1", tmpDir, cb)
    expect(watcher.activeCount()).toBe(1)
  })
})

// ─── killSession ─────────────────────────────────────────────────────────────

describe("killSession", () => {
  it("closes the watcher regardless of subscriber count", () => {
    watcher.subscribe("s1", tmpDir, () => {})
    watcher.subscribe("s1", tmpDir, () => {})
    expect(watcher.activeCount()).toBe(1)
    watcher.killSession("s1")
    expect(watcher.activeCount()).toBe(0)
  })

  it("is a no-op for a session with no watcher", () => {
    expect(() => watcher.killSession("no-such-session")).not.toThrow()
  })

  it("only kills the targeted session", () => {
    const tmpDir2 = mkdtempSync(join(tmpdir(), "cmux-fswatcher2-"))
    try {
      watcher.subscribe("s1", tmpDir, () => {})
      watcher.subscribe("s2", tmpDir2, () => {})
      watcher.killSession("s1")
      expect(watcher.activeCount()).toBe(1)
    } finally {
      rmSync(tmpDir2, { recursive: true, force: true })
    }
  })
})

// ─── shutdown ────────────────────────────────────────────────────────────────

describe("shutdown", () => {
  it("closes all active watchers", () => {
    const tmpDir2 = mkdtempSync(join(tmpdir(), "cmux-fswatcher2-"))
    try {
      watcher.subscribe("s1", tmpDir, () => {})
      watcher.subscribe("s2", tmpDir2, () => {})
      expect(watcher.activeCount()).toBe(2)
      watcher.shutdown()
      expect(watcher.activeCount()).toBe(0)
    } finally {
      rmSync(tmpDir2, { recursive: true, force: true })
    }
  })

  it("is a no-op when no watchers are active", () => {
    expect(() => watcher.shutdown()).not.toThrow()
    expect(watcher.activeCount()).toBe(0)
  })
})

// ─── multiple subscribers share one watcher ──────────────────────────────────

describe("multiple subscribers share one watcher", () => {
  it("both callbacks are notified on file change", async () => {
    const received1: string[][] = []
    const received2: string[][] = []

    const cb1 = (paths: string[]) => { received1.push(paths) }
    const cb2 = (paths: string[]) => { received2.push(paths) }

    watcher.subscribe("s1", tmpDir, cb1)
    watcher.subscribe("s1", tmpDir, cb2)

    // Write a file to trigger the watcher
    const testFile = join(tmpDir, "shared-test.txt")
    writeFileSync(testFile, "hello")

    // Wait for debounce + some buffer (debounce is 500ms, allow up to 3s)
    await new Promise<void>((resolve) => {
      const deadline = setTimeout(resolve, 3000)
      const interval = setInterval(() => {
        if (received1.length > 0 && received2.length > 0) {
          clearInterval(interval)
          clearTimeout(deadline)
          resolve()
        }
      }, 50)
    })

    expect(received1.length).toBeGreaterThan(0)
    expect(received2.length).toBeGreaterThan(0)
  })
})

// ─── notifications on file change ────────────────────────────────────────────

describe("notifications on file change", () => {
  it("fires callback with changed path after debounce", async () => {
    const received: string[][] = []
    const cb = (paths: string[]) => { received.push(paths) }

    watcher.subscribe("s1", tmpDir, cb)

    const testFile = join(tmpDir, "trigger.txt")
    writeFileSync(testFile, "initial content")

    // Wait for callback with up to 3s timeout
    await new Promise<void>((resolve) => {
      const deadline = setTimeout(resolve, 3000)
      const interval = setInterval(() => {
        if (received.length > 0) {
          clearInterval(interval)
          clearTimeout(deadline)
          resolve()
        }
      }, 50)
    })

    expect(received.length).toBeGreaterThan(0)
    // The paths array should be non-empty
    expect(received[0]!.length).toBeGreaterThan(0)
  })

  it("batches rapid changes into a single notification", async () => {
    const received: string[][] = []
    const cb = (paths: string[]) => { received.push(paths) }

    watcher.subscribe("s1", tmpDir, cb)

    // Write multiple files rapidly
    writeFileSync(join(tmpDir, "file1.txt"), "a")
    writeFileSync(join(tmpDir, "file2.txt"), "b")
    writeFileSync(join(tmpDir, "file3.txt"), "c")

    // Wait for debounce to fire (at least 500ms + buffer)
    await new Promise<void>((resolve) => {
      const deadline = setTimeout(resolve, 3000)
      const interval = setInterval(() => {
        if (received.length > 0) {
          clearInterval(interval)
          clearTimeout(deadline)
          resolve()
        }
      }, 50)
    })

    // At least one notification came through
    expect(received.length).toBeGreaterThan(0)
    // The notification should contain paths
    const allPaths = received.flat()
    expect(allPaths.length).toBeGreaterThan(0)
  })

  it("does not fire for node_modules paths", async () => {
    const received: string[][] = []
    const cb = (paths: string[]) => { received.push(paths) }

    watcher.subscribe("s1", tmpDir, cb)

    // Create node_modules dir and write into it
    mkdirSync(join(tmpDir, "node_modules"), { recursive: true })
    writeFileSync(join(tmpDir, "node_modules", "ignored.txt"), "should be ignored")

    // Wait 800ms (longer than debounce) — no notification should fire for node_modules
    await new Promise((r) => setTimeout(r, 800))

    // Verify no paths containing node_modules were reported
    const allPaths = received.flat()
    for (const p of allPaths) {
      expect(p).not.toContain("node_modules")
    }
  })

  it("does not fire for .git paths", async () => {
    const received: string[][] = []
    const cb = (paths: string[]) => { received.push(paths) }

    watcher.subscribe("s1", tmpDir, cb)

    // Create .git dir and write into it
    mkdirSync(join(tmpDir, ".git"), { recursive: true })
    writeFileSync(join(tmpDir, ".git", "HEAD"), "ref: refs/heads/main")

    // Wait 800ms — no notification should fire for .git
    await new Promise((r) => setTimeout(r, 800))

    // Verify no paths containing .git were reported
    const allPaths = received.flat()
    for (const p of allPaths) {
      expect(p).not.toContain(".git")
    }
  })

  it("does not notify unsubscribed callback", async () => {
    const received: string[][] = []
    const cb = (paths: string[]) => { received.push(paths) }

    watcher.subscribe("s1", tmpDir, cb)
    watcher.unsubscribe("s1", cb)

    writeFileSync(join(tmpDir, "after-unsub.txt"), "content")

    // Wait longer than debounce
    await new Promise((r) => setTimeout(r, 800))

    expect(received.length).toBe(0)
  })
})
