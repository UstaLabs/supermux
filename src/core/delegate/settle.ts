// A delegated child that goes quiet without reporting (decision #21, 2026-10-30). A child is settled when
// its turn is idle, no input waits for it, none of its background work runs and no request waits on an
// answer; it has reported once it messaged its parent since its current task began. Settled and not
// reported: supermux nudges the child once, and if it settles again still without a report, tells the
// parent once. Re-checked on every change (debounced, so the gap between back-to-back turns doesn't
// count); never on a timeout, so background work that never ends holds it.

/** How long a child must stay settled before it counts: the gap between queued turns is shorter. */
export const SETTLE_DEBOUNCE_MS = 2000

/** What the child is doing now. */
export type SettleInputs = {
  /** Its agent runs (a crashed child gets the crash notice instead). */
  alive: boolean
  /** A turn runs. */
  running: boolean
  /** Inbox messages not yet handed over, or input queued in its adapter. */
  queuedInput: boolean
  /** Its own background subagents or background tasks. */
  backgroundWork: boolean
  /** An approval or question waits on an answer. */
  pendingRequest: boolean
}

/** One task of a child: from the message from its parent (or the user) that began it. */
export type ChildTask = {
  /** The child messaged its parent. */
  reported: boolean
  /** The parent heard a failed-turn notice for it. */
  told: boolean
  nudged: boolean
  /** A turn started after the nudge: settling again now means the nudge didn't help. */
  turnSinceNudge: boolean
  notified: boolean
}

export type SettleDecision = "none" | "nudge" | "notify"

/** `inputs` undefined: not a child to watch now (council member, child or parent archived). */
export function evaluate(task: ChildTask | undefined, inputs: SettleInputs | undefined): SettleDecision {
  if (!task || !inputs) return "none"
  const settled = inputs.alive && !inputs.running && !inputs.queuedInput && !inputs.backgroundWork && !inputs.pendingRequest
  if (!settled || task.reported || task.told || task.notified) return "none"
  if (!task.nudged) return "nudge"
  return task.turnSinceNudge ? "notify" : "none"
}

type Named = { name: string; id: string }

export function nudgeText(parent: Named): string {
  return `You haven't reported to your parent [${parent.name}:${parent.id}] yet. ` +
    `Send it your result with message_session({ to: ["${parent.id}"], text, interrupt: false }). ` +
    "If you are blocked or waiting for something, tell it that instead. If there is nothing to report, say so."
}

export function quietText(child: Named, lastReply: string | undefined): string {
  const head = `Your subagent [${child.name}:${child.id}] is idle with nothing running and hasn't reported.`
  return lastReply ? `${head} Its last message:\n${lastReply}` : `${head} It sent no message.`
}

/**
 * Startup: a child's task is still open when the newest input from its parent or the user (ISO times)
 * is newer than its newest message to its parent.
 */
export function taskOpenSince(inputs: Array<string | undefined>, lastReport: string | undefined): boolean {
  const newest = inputs.filter((t): t is string => !!t).sort().at(-1)
  return !!newest && (!lastReport || newest > lastReport)
}

export type ChildSettleDeps = {
  /** The parent of a delegated child; undefined for a top-level session or a council member. */
  parentOf(id: string): string | undefined
  /** The child's inputs now; undefined when it is not to be watched now. */
  inputs(id: string): SettleInputs | undefined
  /** supermux's nudge to the child (nudgeText). */
  nudge(childId: string): void
  /** supermux's notice to the parent (quietText). */
  notify(childId: string): void
  timer: { set(fn: () => void, ms: number): unknown; clear(handle: unknown): void }
  debounceMs?: number
}

/** `awaitingTurn`: the task's input was handed over, but no turn has started since (a busy child queues it). */
type Watched = ChildTask & { timer?: unknown; awaitingTurn: boolean }

const freshTask = (awaitingTurn: boolean, timer?: unknown): Watched =>
  ({ reported: false, told: false, nudged: false, turnSinceNudge: false, notified: false, awaitingTurn, timer })

export class ChildSettle {
  /** Children with a task being watched: begun in this process, or found still open at startup (seed). */
  private readonly tasks = new Map<string, Watched>()
  /** Open holds per child, across its tasks. */
  private readonly holds = new Map<string, number>()

  constructor(private readonly deps: ChildSettleDeps) {}

  /** Input handed over to `id`: from a session (its id), or from the user (undefined). Its parent's or the user's begins a task. */
  inbound(id: string, from: string | undefined): void {
    const parent = this.deps.parentOf(id)
    if (!parent || (from !== undefined && from !== parent)) return this.changed(id)
    this.tasks.set(id, freshTask(true, this.tasks.get(id)?.timer))
    this.changed(id)
  }

  /**
   * Startup: watch a child whose task was still open when the broker stopped (taskOpenSince). A task
   * begun since startup is left alone; so is a session that is not a delegated child.
   */
  seed(id: string): void {
    if (!this.deps.parentOf(id) || this.tasks.has(id)) return
    this.tasks.set(id, freshTask(false))
    this.changed(id)
  }

  /**
   * An inbox message from `from` to `to`: a child's message to its parent is its report. Not before a
   * turn has started on the task: until then it comes from the turn before (the previous task's report).
   */
  sent(from: string, to: string): void {
    const task = this.tasks.get(from)
    if (task && !task.awaitingTurn && this.deps.parentOf(from) === to) task.reported = true
  }

  /** The parent heard a notice for this child's current task (its crash). */
  told(id: string): void {
    const task = this.tasks.get(id)
    if (task) task.told = true
  }

  /**
   * Keep the child unsettled until `release` (a failed turn's notice is being decided). `told` marks the
   * task the hold was taken for: a task begun since is not the one the notice is about.
   */
  hold(id: string): { release(): void; told(): void } {
    const task = this.tasks.get(id)
    this.holds.set(id, (this.holds.get(id) ?? 0) + 1)
    let released = false
    return {
      told: () => { if (task) task.told = true },
      release: () => {
        if (released) return
        released = true
        const left = (this.holds.get(id) ?? 1) - 1
        if (left > 0) this.holds.set(id, left)
        else this.holds.delete(id)
        this.changed(id)
      },
    }
  }

  turnStarted(id: string): void {
    const task = this.tasks.get(id)
    if (task) {
      task.awaitingTurn = false
      if (task.nudged) task.turnSinceNudge = true
    }
    this.changed(id)
  }

  /** Something the inputs read moved: check again once it has been quiet for the debounce. */
  changed(id: string): void {
    const task = this.tasks.get(id)
    if (!task) return
    if (task.timer !== undefined) this.deps.timer.clear(task.timer)
    task.timer = this.deps.timer.set(() => {
      task.timer = undefined
      this.check(id, task)
    }, this.deps.debounceMs ?? SETTLE_DEBOUNCE_MS)
  }

  /** The child was archived or killed (its own notices speak for it). */
  forget(id: string): void {
    const task = this.tasks.get(id)
    if (task?.timer !== undefined) this.deps.timer.clear(task.timer)
    this.tasks.delete(id)
  }

  private check(id: string, task: Watched): void {
    if (this.tasks.get(id) !== task || this.holds.has(id)) return
    const decision = evaluate(task, this.deps.inputs(id))
    if (decision === "nudge") {
      task.nudged = true
      this.deps.nudge(id)
    } else if (decision === "notify") {
      task.notified = true
      this.deps.notify(id)
    }
  }
}
