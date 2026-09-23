// The WorkspaceTerminalBackend contract, as an executable suite every backend
// runs against itself.
//
// There are three implementations of this interface — the recording one that
// pins the invariants, zmx on POSIX and sessiond/ConPTY on Windows — and the
// bugs worth catching are the ones where two of them disagree: a reconnect
// that resurrects a dead shell on one platform and reports an exit on the
// other is not a difference a client can be asked to cope with. So the
// assertions live here once, and each backend supplies a WORLD: a way to
// observe its targets, to stall a create or an attach, and to end a target's
// process.
//
// Everything the suite asserts is observable through the public interface plus
// that world. Nothing reaches into a backend's internals, because a test that
// does can only ever be written for one of them.
import { describe, expect, test } from "bun:test"
import {
  isWorkspaceTerminalError,
  type WorkspaceTerminalBackend,
  type WorkspaceTerminalEvent,
  type WorkspaceTerminalKey,
} from "./workspace-backend"

/** Keys the suite uses. `NEIGHBOUR` shares a PREFIX with A's scope and nothing
 * else: `closeScope("w:alpha")` taking "w:alphabet" with it is the bug. */
export const CONTRACT_A: WorkspaceTerminalKey = { scope: "w:alpha", terminalId: "main" }
export const CONTRACT_B: WorkspaceTerminalKey = { scope: "w:alpha", terminalId: "second" }
export const CONTRACT_NEIGHBOUR: WorkspaceTerminalKey = { scope: "w:alphabet", terminalId: "main" }

/** What `ensure` is called with. A world must be able to satisfy this — a real
 * cwd and a real shell, or a probe that says so. */
export const CONTRACT_ENSURE = { cwd: "/w", shell: "/bin/bash", env: {}, cols: 80, rows: 24 }

export type Gate = { release(): void }

/**
 * A backend plus the handles the suite needs to drive it. Implemented once per
 * backend, in that backend's own test file.
 */
export interface WorkspaceBackendWorld {
  backend: WorkspaceTerminalBackend
  /** A BRAND-NEW backend object over the same running targets: a broker
   * restart. Whatever it can still see is what survived the process. */
  restart(): WorkspaceTerminalBackend
  /** How many targets have been created, ever. The property "a reconnect does
   * not create" is a statement about this number, not about a call log. */
  creates(): number
  /** Everything that reached this target's pty, in arrival order. */
  pty(key: WorkspaceTerminalKey): string
  /** The geometry the target's pty currently believes. */
  size(key: WorkspaceTerminalKey): { cols: number; rows: number }
  /** The target's PROCESS ends. The target is gone; nothing respawns it. */
  exit(key: WorkspaceTerminalKey, status: { known: boolean; code: number | null; signal: number | null }): Promise<void>
  /** Hold the next create until `release()`. */
  gateCreate(): Gate
  /** Hold the next attach until `release()`. */
  gateAttach(): Gate
  dispose(): Promise<void>
}

export function recorder(): { events: WorkspaceTerminalEvent[]; emit: (e: WorkspaceTerminalEvent) => Promise<void> } {
  const events: WorkspaceTerminalEvent[] = []
  return { events, emit: async (e: WorkspaceTerminalEvent) => { events.push(e) } }
}

export function deferred(): { promise: Promise<void>; resolve: () => void } {
  let resolve!: () => void
  const promise = new Promise<void>(r => { resolve = r })
  return { promise, resolve }
}

const bytes = (value: string) => new TextEncoder().encode(value)
const ids = (list: Array<{ terminalId: string }>) => list.map(entry => entry.terminalId)

/**
 * Run the contract against one backend.
 *
 * `makeWorld` is called fresh per test: a backend that leaked state between
 * two of these would be a backend that leaks it between two workspaces.
 */
export function runWorkspaceBackendContract(
  label: string,
  makeWorld: () => Promise<WorkspaceBackendWorld>,
): void {
  describe(`workspace terminal backend contract (${label})`, () => {
    const withWorld = (name: string, body: (world: WorkspaceBackendWorld) => Promise<void>) =>
      test(name, async () => {
        const world = await makeWorld()
        try {
          await body(world)
        } finally {
          await world.dispose()
        }
      })

    withWorld("ensure creates once; a repeat ensure is a no-op", async world => {
      await world.backend.ensure(CONTRACT_A, CONTRACT_ENSURE)
      await world.backend.ensure(CONTRACT_A, CONTRACT_ENSURE)
      expect(world.creates()).toBe(1)
      expect(await world.backend.exists(CONTRACT_A)).toBe(true)
    })

    withWorld("two simultaneous first creates yield one target", async world => {
      const gate = world.gateCreate()
      const both = Promise.all([
        world.backend.ensure(CONTRACT_A, CONTRACT_ENSURE),
        world.backend.ensure(CONTRACT_A, CONTRACT_ENSURE),
      ])
      gate.release()
      await both
      expect(world.creates()).toBe(1)
      expect(await world.backend.list("w:alpha")).toHaveLength(1)
    })

    withWorld("attach opens ONE epoch and marks the replay boundary", async world => {
      await world.backend.ensure(CONTRACT_A, CONTRACT_ENSURE)
      const { events, emit } = recorder()
      await world.backend.attachExisting(CONTRACT_A, "v1", emit)

      const types = events.map(event => event.type)
      expect(types[0]).toBe("reset")
      expect(types[1]).toBe("replay-start")
      expect(types).toContain("replay-end")
      // Nothing is drawn before the epoch opens, and nothing that claims to be
      // replay arrives after it closes.
      expect(types.indexOf("output")).toBeGreaterThan(types.indexOf("replay-start"))
      expect(types.indexOf("output")).toBeLessThan(types.indexOf("replay-end"))
      const epochs = events.filter(event => "epoch" in event).map(event => (event as { epoch: string }).epoch)
      expect(epochs.length).toBe(3)
      expect(new Set(epochs).size).toBe(1)
    })

    withWorld("a reconnect attaches only — it never creates", async world => {
      await world.backend.ensure(CONTRACT_A, CONTRACT_ENSURE)
      const first = await world.backend.attachExisting(CONTRACT_A, "v1", recorder().emit)
      await first.detach()

      const again = await world.backend.attachExisting(CONTRACT_A, "v1", recorder().emit)
      expect(again.write(bytes("l"))).toBe(true)
      expect(world.creates()).toBe(1)
    })

    withWorld("attach to a missing target rejects with target-not-found", async world => {
      const error = await world.backend.attachExisting(CONTRACT_A, "v1", recorder().emit)
        .then(() => null, (e: unknown) => e)
      expect(isWorkspaceTerminalError(error, "target-not-found")).toBe(true)
    })

    withWorld("a target that EXITED is reported and never resurrected by attach", async world => {
      await world.backend.ensure(CONTRACT_A, CONTRACT_ENSURE)
      const { events, emit } = recorder()
      await world.backend.attachExisting(CONTRACT_A, "v1", emit)

      await world.exit(CONTRACT_A, { known: true, code: 0, signal: null })
      expect(events.at(-1)).toEqual({ type: "exit", known: true, code: 0, signal: null })
      expect(await world.backend.exists(CONTRACT_A)).toBe(false)

      // `tmux new-session -A` would hand back a brand-new shell here.
      const error = await world.backend.attachExisting(CONTRACT_A, "v2", recorder().emit)
        .then(() => null, (e: unknown) => e)
      expect(isWorkspaceTerminalError(error, "target-not-found")).toBe(true)
      expect(world.creates()).toBe(1)
    })

    withWorld("detach preserves the target", async world => {
      await world.backend.ensure(CONTRACT_A, CONTRACT_ENSURE)
      const viewer = await world.backend.attachExisting(CONTRACT_A, "v1", recorder().emit)
      await viewer.detach()

      expect(viewer.write(bytes("x"))).toBe(false)
      expect(await world.backend.exists(CONTRACT_A)).toBe(true)
      expect(ids(await world.backend.list("w:alpha"))).toEqual(["main"])
      await viewer.detach() // idempotent
    })

    withWorld("close deletes the target and its viewers", async world => {
      await world.backend.ensure(CONTRACT_A, CONTRACT_ENSURE)
      const viewer = await world.backend.attachExisting(CONTRACT_A, "v1", recorder().emit)

      await world.backend.close(CONTRACT_A)
      expect(await world.backend.exists(CONTRACT_A)).toBe(false)
      expect(await world.backend.list("w:alpha")).toEqual([])
      expect(viewer.write(bytes("x"))).toBe(false)
      expect(viewer.reply(bytes("x"))).toBe(false)
      await world.backend.close(CONTRACT_A) // idempotent
    })

    withWorld("close during an IN-FLIGHT attach rejects and leaves no viewer behind", async world => {
      await world.backend.ensure(CONTRACT_A, CONTRACT_ENSURE)
      const gate = world.gateAttach()
      const attaching = world.backend.attachExisting(CONTRACT_A, "v1", recorder().emit)
      await world.backend.close(CONTRACT_A)
      gate.release()

      const error = await attaching.then(() => null, (e: unknown) => e)
      expect(isWorkspaceTerminalError(error, "target-not-found")).toBe(true)
      expect(await world.backend.exists(CONTRACT_A)).toBe(false)
    })

    withWorld("shutdownViewers closes viewers ONLY — targets keep running", async world => {
      await world.backend.ensure(CONTRACT_A, CONTRACT_ENSURE)
      await world.backend.ensure(CONTRACT_B, CONTRACT_ENSURE)
      const one = await world.backend.attachExisting(CONTRACT_A, "v1", recorder().emit)
      const two = await world.backend.attachExisting(CONTRACT_B, "v2", recorder().emit)

      await world.backend.shutdownViewers()
      expect(one.write(bytes("x"))).toBe(false)
      expect(two.write(bytes("x"))).toBe(false)
      expect(await world.backend.exists(CONTRACT_A)).toBe(true)
      expect(await world.backend.exists(CONTRACT_B)).toBe(true)
    })

    withWorld("list survives a reconstructed backend, oldest first", async world => {
      await world.backend.ensure(CONTRACT_A, CONTRACT_ENSURE)
      await world.backend.ensure(CONTRACT_B, CONTRACT_ENSURE)
      await world.backend.attachExisting(CONTRACT_A, "v1", recorder().emit)
      await world.backend.shutdownViewers()

      // Broker restart: brand-new backend object, same running targets.
      const after = world.restart()
      expect(ids(await after.list("w:alpha"))).toEqual(["main", "second"])

      // And a reconnect after that restart still only ATTACHES.
      await after.attachExisting(CONTRACT_A, "v1", recorder().emit)
      expect(world.creates()).toBe(2)
    })

    withWorld("closeScope touches exactly its own scope, not a neighbouring one", async world => {
      await world.backend.ensure(CONTRACT_A, CONTRACT_ENSURE)
      await world.backend.ensure(CONTRACT_B, CONTRACT_ENSURE)
      await world.backend.ensure(CONTRACT_NEIGHBOUR, CONTRACT_ENSURE)

      await world.backend.closeScope("w:alpha")
      expect(await world.backend.list("w:alpha")).toEqual([])
      expect(await world.backend.list("w:alphabet")).toHaveLength(1)
    })

    withWorld("size ownership follows the newest focus claim", async world => {
      await world.backend.ensure(CONTRACT_A, CONTRACT_ENSURE)
      const first = recorder()
      const second = recorder()
      const one = await world.backend.attachExisting(CONTRACT_A, "v1", first.emit)
      const two = await world.backend.attachExisting(CONTRACT_A, "v2", second.emit)

      await one.focus(true, 100, 40)
      expect(first.events.at(-1)).toEqual({ type: "owner", enabled: true })
      expect(world.size(CONTRACT_A).cols).toBe(100)

      // The loser is TOLD. The zmx daemon only messages the winner, so this is
      // the broker's deduction; a viewer that is not told keeps believing it
      // owns the size until a resize silently vanishes.
      await two.focus(true, 120, 50)
      expect(first.events.at(-1)).toEqual({ type: "owner", enabled: false })
      expect(second.events.at(-1)).toEqual({ type: "owner", enabled: true })
      expect(world.size(CONTRACT_A).cols).toBe(120)

      // A background viewer may keep reporting layout; it must NOT resize the pty.
      await one.resize(10, 10)
      expect(world.size(CONTRACT_A).cols).toBe(120)
    })

    withWorld("user input from any viewer reaches the pty", async world => {
      await world.backend.ensure(CONTRACT_A, CONTRACT_ENSURE)
      const one = await world.backend.attachExisting(CONTRACT_A, "v1", recorder().emit)
      const two = await world.backend.attachExisting(CONTRACT_A, "v2", recorder().emit)
      await two.focus(true, 80, 24)

      // Typing is typing: the background viewer's keystrokes are not dropped,
      // and they do not move the lease.
      expect(one.write(bytes("ls"))).toBe(true)
      expect(two.write(bytes("\r"))).toBe(true)
      expect(world.pty(CONTRACT_A)).toContain("ls")
      expect(world.pty(CONTRACT_A)).toContain("\r")
      expect(world.size(CONTRACT_A).cols).toBe(80)
    })

    withWorld("a non-owner's terminal reply is refused, never queued", async world => {
      await world.backend.ensure(CONTRACT_A, CONTRACT_ENSURE)
      const one = await world.backend.attachExisting(CONTRACT_A, "v1", recorder().emit)
      const two = await world.backend.attachExisting(CONTRACT_A, "v2", recorder().emit)
      await two.focus(true, 80, 24)

      // Every viewer renders the same DA1 query and every one of them answers.
      // Anything past the owner's answer is read by the shell as typed input.
      expect(one.reply(bytes("\x1b[?62c"))).toBe(false)
      expect(world.pty(CONTRACT_A)).not.toContain("\x1b[?62c")

      expect(two.reply(bytes("\x1b[?62c"))).toBe(true)
      expect(world.pty(CONTRACT_A)).toContain("\x1b[?62c")
    })
  })
}
