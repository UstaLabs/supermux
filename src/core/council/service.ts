// A council (spec 2026-10-28 §5; lifecycle: docs/2026-10-31-council-lifecycle-design.md): supermux code, not an
// agent. It sends the members the question and each round's focus with the others' reports (by letter only),
// forwards every report to the caller as it arrives (with the member's name), and tells the caller when a member
// can't start, crashes, is stopped or a turn of it fails. The caller owns it: it adds and removes members and ends
// it with a verdict. An ended council is never touched again.
import type { AgentKind } from "../../shared/agents"
import type { Session } from "../session-manager/types"
import type { InboxCouncilTag, InboxSender } from "../inbox/store"
import type { CouncilEndedBy, CouncilRecord, CouncilStore, MemberRoundStatus } from "./store"

export type CouncilView = {
  id: string
  name: string
  question: string
  round: number
  focus?: string
  status: "open" | "ended"
  endedBy?: CouncilEndedBy
  verdict?: string
  endedAt?: string
  callId?: string
  createdAt: string
  members: Array<{ letter: string; session: string; name: string; agent: AgentKind; model?: string; label: string; status: MemberRoundStatus | "working" }>
  rounds: Array<{ round: number; focus?: string; startedAt: string; completedAt?: string; reports: Array<{ id: string; letter: string; seq: number; text: string; at: string }> }>
}

export type CouncilDeps = {
  store: CouncilStore
  /** A session row, archived ones included. */
  session(id: string): Session | undefined
  isLive(id: string): boolean
  /** How the caller sees a member's agent: its model's name, else the agent's ("GPT-6", "Grok"). */
  label(member: Session): string
  /** A message to a session's inbox (queued behind its current turn). */
  send(from: InboxSender, to: string, text: string, council?: InboxCouncilTag): void
  /** A notice from supermux to a session's inbox. */
  notify(to: string, text: string): void
  lastReply(sessionId: string): string | undefined
  /** Stops and archives a member on the council's behalf (main.ts: killAndArchive, ended by the parent: no notice). */
  kill(sessionId: string): Promise<void>
  /** The caller's council card changed (snapshot `councils`, `council_update`). */
  changed(callerSessionId: string, view: CouncilView): void
  /** The member is really working now (its turn runs, or input waits for it). Absent: never. */
  busy?(sessionId: string): boolean
}

/** How supermux's nudge to a member begins: the startup seed recognises one in a member's inbox by it. */
export const MEMBER_NUDGE_PREFIX = "You haven't answered "

/**
 * Ends every message the council sends a member (decided 2026-10-29): a member that answered in its own
 * chat instead of calling council_report left its caller without the answer.
 */
const REPORT_REMINDER = "\n\nAnswer with council_report({ text })."

export class Council {
  constructor(private readonly deps: CouncilDeps) {}

  /** The council was saved: the caller's card shows it while its members start. */
  opened(councilId: string): void {
    const c = this.deps.store.get(councilId)
    if (c) this.emit(c)
  }

  /**
   * A member is up. In round 1 its only starting message is the question (spec §5); a member added later also
   * gets the current round's focus and the previous round's reports. A member that came up after the council
   * ended is stopped instead.
   */
  memberStarted(memberId: string): void {
    const membership = this.deps.store.membership(memberId)
    const c = membership && this.deps.store.get(membership.councilId)
    if (!membership || !c) return
    if (c.status === "ended") {
      if (this.deps.isLive(memberId)) void this.deps.kill(memberId).catch(() => {})
      return
    }
    // Stopped while it was starting: a message would restore it.
    if (this.deps.isLive(memberId)) {
      const text = c.currentRound === 1 ? c.question : `${c.question}\n\n${this.roundMessage(c, c.currentRound, membership.letter, memberId)}`
      this.deps.send(this.sender(c), memberId, `${text}${REPORT_REMINDER}`)
    }
    this.emit(c)
  }

  /** A member couldn't start: its status is "failed" and a live caller hears why. */
  memberStartFailed(memberId: string, error: string): void {
    const { membership, c } = this.open(memberId)
    if (!membership || !c) return
    this.deps.store.setStatus(c.id, c.currentRound, memberId, "failed")
    if (this.deps.isLive(c.callerSessionId)) {
      const member = this.deps.session(memberId)
      this.deps.notify(c.callerSessionId, `${c.name} · ${membership.letter}${member ? ` [${member.name}:${member.id}]` : ""} couldn't start:\n${error}`)
    }
    this.emitOrEndEmpty(c, true)
  }

  /** council_report: the member's answer for the current round, forwarded to the caller at once. */
  report(memberId: string, text: string): { id: string } {
    const membership = this.deps.store.membership(memberId)
    const c = membership && this.deps.store.get(membership.councilId)
    if (!membership || !c) throw new Error("only council members can report")
    if (c.status === "ended") throw new Error(`${c.name} has ended`)
    const report = this.deps.store.addReport(c.id, c.currentRound, memberId, text)
    this.deps.store.setStatus(c.id, c.currentRound, memberId, "reported")
    const member = this.deps.session(memberId)
    // A message to an archived caller would restore it (spec §1.3 step 0): only a live one hears.
    if (member && this.deps.isLive(c.callerSessionId)) {
      const head = `${c.name} · round ${c.currentRound} · ${membership.letter} — ${this.deps.label(member)} [${member.name}:${member.id}]:`
      this.deps.send(this.sender(c), c.callerSessionId, `${head}\n${text}`, { id: c.id, round: c.currentRound, letter: membership.letter })
    }
    this.emit(c)
    return { id: report.id }
  }

  /** council_round: the next round, its focus and the round that ended, to every live member (spec §5). */
  startRound(callerId: string, councilId: string, focus: string): { round: number } {
    const c = this.callersOpen(callerId, councilId, "start a round")
    const round = this.deps.store.nextRound(c.id, focus)
    const next = this.deps.store.get(c.id)!
    for (const m of this.deps.store.members(c.id)) {
      // A killed member is archived; a message would restore it.
      if (!this.deps.isLive(m.sessionId)) continue
      this.deps.send(this.sender(c), m.sessionId, `${this.roundMessage(next, round, m.letter, m.sessionId)}${REPORT_REMINDER}`)
    }
    this.emit(next)
    return { round }
  }

  /** council_remove: the caller stops these members (no notice: it asked). Unknown or gone letters are refused. */
  async remove(callerId: string, councilId: string, letters: string[]): Promise<{ removed: string[] }> {
    const c = this.callersOpen(callerId, councilId, "remove members")
    const members = this.deps.store.members(c.id)
    const picked = letters.map((letter) => {
      const m = members.find((x) => x.letter === letter)
      if (!m) throw new Error(`${c.name} has no member ${letter}`)
      if (!this.alive(c, m.sessionId)) throw new Error(`${c.name} · ${letter} is not running`)
      return m
    })
    // Marked first: the kill's update already shows the council without them.
    for (const m of picked) this.deps.store.markRemoved(c.id, m.sessionId)
    for (const m of picked) if (this.deps.isLive(m.sessionId)) await this.deps.kill(m.sessionId)
    this.emit(this.deps.store.get(c.id) ?? c)
    return { removed: picked.map((m) => m.letter) }
  }

  /**
   * council_end (by: "caller", with its verdict) or Stop council (by: "user"). The council is marked ended first,
   * so its members' stops send nothing; then the members still running are stopped. Only the user's end is told
   * to the caller.
   */
  async end(councilId: string, by: "caller" | "user", opts: { callerId?: string; verdict?: string } = {}): Promise<void> {
    const c = this.deps.store.get(councilId)
    if (!c) throw new Error(`unknown council: ${councilId}`)
    if (by === "caller") {
      if (c.callerSessionId !== opts.callerId) throw new Error("only the council's caller can end it")
      if (!opts.verdict?.trim()) throw new Error("verdict must not be empty")
    }
    if (!this.deps.store.end(c.id, by, by === "caller" ? opts.verdict!.trim() : undefined)) throw new Error(`${c.name} has already ended`)
    const ended = this.deps.store.get(c.id)!
    this.emit(ended)
    if (by === "user" && this.deps.isLive(c.callerSessionId)) this.deps.notify(c.callerSessionId, `${c.name} was ended by the user.`)
    for (const m of this.deps.store.members(c.id)) {
      if (this.deps.isLive(m.sessionId)) await this.deps.kill(m.sessionId).catch(() => {})
    }
  }

  /** Decision #21: the member went idle without answering the current round; supermux reminds it once. */
  nudgeMember(memberId: string): void {
    const { c } = this.open(memberId)
    if (!c) return
    this.deps.notify(memberId, `${MEMBER_NUDGE_PREFIX}${c.name} round ${c.currentRound} yet. Answer with council_report({ text }). If you can't, say why in council_report.`)
  }

  /** Decision #21: nudged and idle again without an answer: the caller hears it once (only a live one). */
  memberQuiet(memberId: string): void {
    const { membership, c } = this.open(memberId)
    const member = this.deps.session(memberId)
    if (!membership || !c || !member || !this.deps.isLive(c.callerSessionId)) return
    const last = this.deps.lastReply(memberId)
    const head = `${c.name} · ${membership.letter} [${member.name}:${member.id}] is idle and hasn't answered round ${c.currentRound}.`
    this.deps.notify(c.callerSessionId, last ? `${head} Its last message:\n${last}` : `${head} It sent no message.`)
  }

  /** The member's core session failed. */
  memberCrashed(memberId: string): void {
    this.memberEnded(memberId, "crashed")
  }

  /**
   * The member was archived. The cascade (the caller being killed) ends the council without a word; a caller
   * that stopped it (council_remove, kill_session) already knows (decision #15); the user's stop is said so.
   */
  memberArchived(member: Session, how: { cascade?: boolean; endedBy?: "parent" | "client" }): void {
    if (how.cascade) {
      const c = this.open(member.id).c
      if (c) this.deps.store.end(c.id, "empty")
      return
    }
    this.memberEnded(member.id, "killed", how.endedBy === "parent", how.endedBy === "client")
  }

  /**
   * A turn of the member ended in an error and it still runs (decision #17): the caller hears it. A
   * report the member already made this round keeps its status, and so does a newer turn of the member
   * that has started since (`status: false`).
   */
  memberTurnFailed(memberId: string, error: string, opts: { status?: boolean } = {}): void {
    const { membership, c } = this.open(memberId)
    const member = this.deps.session(memberId)
    if (!membership || !c || !member || !this.deps.isLive(memberId)) return
    if (opts.status !== false && this.deps.store.statuses(c.id, c.currentRound).get(memberId) !== "reported") {
      this.deps.store.setStatus(c.id, c.currentRound, memberId, "failed")
    }
    // A message to an archived caller would restore it (spec §1.3 step 0): only a live one hears.
    if (this.deps.isLive(c.callerSessionId)) {
      this.deps.notify(c.callerSessionId, `${c.name} · ${membership.letter} [${member.name}:${member.id}] stopped with an error:\n${error}`)
    }
    this.emit(c)
  }

  /** A turn of the member started: a turn that failed this round no longer stands for it (a report does). */
  memberTurnStarted(memberId: string): void {
    const { c } = this.open(memberId)
    if (!c) return
    if (this.deps.store.clearStatus(c.id, c.currentRound, memberId, "failed")) this.emit(c)
  }

  /** True while the member belongs to a council that is still open: only those are watched (design §4). */
  watched(memberId: string): boolean {
    return !!this.open(memberId).c
  }

  /**
   * An open council of this caller still waits on a member's report for its current round (decision #21): a
   * live member that hasn't reported and is busy. An idle member that never reported (allowed) holds nothing.
   */
  waiting(callerSessionId: string): boolean {
    return this.forCaller(callerSessionId).some((c) =>
      c.status === "open" && c.members.some((m) => m.status === "working" && !!this.deps.busy?.(m.session)))
  }

  forCaller(callerSessionId: string): CouncilView[] {
    return this.deps.store.byCaller(callerSessionId).map((c) => this.view(c))
  }

  /** The member's council, only while it is open. */
  private open(memberId: string): { membership?: { councilId: string; letter: string }; c?: CouncilRecord } {
    const membership = this.deps.store.membership(memberId)
    const c = membership && this.deps.store.get(membership.councilId)
    return c?.status === "open" ? { membership, c } : {}
  }

  /** An open council of this caller, or the reason it can't be acted on. */
  private callersOpen(callerId: string, councilId: string, what: string): CouncilRecord {
    const c = this.deps.store.get(councilId)
    if (!c) throw new Error(`unknown council: ${councilId}`)
    if (c.callerSessionId !== callerId) throw new Error(`only the council's caller can ${what}`)
    if (c.status === "ended") throw new Error(`${c.name} has ended`)
    return c
  }

  /** Running or still starting: its session is live, or it has none yet and its start didn't fail. */
  private alive(c: CouncilRecord, memberId: string): boolean {
    if (this.deps.session(memberId)) return this.deps.isLive(memberId)
    return !this.deps.store.hadStatus(c.id, memberId, "failed")
  }

  private memberEnded(memberId: string, status: "crashed" | "killed", quiet = false, byUser = false): void {
    const { membership, c } = this.open(memberId)
    const member = this.deps.session(memberId)
    if (!membership || !c || !member) return
    this.deps.store.setStatus(c.id, c.currentRound, memberId, status)
    if (!quiet && this.deps.isLive(c.callerSessionId)) {
      const last = this.deps.lastReply(memberId)
      const what = status === "crashed" ? "crashed" : byUser ? "was stopped by the user" : "was killed"
      this.deps.notify(c.callerSessionId, [`${c.name} · ${membership.letter} [${member.name}:${member.id}] ${what}.`, last ? `Its last message:\n${last}` : "It sent no message."].join("\n"))
    }
    this.emitOrEndEmpty(c, !quiet)
  }

  /** The council's last member is gone: it ends (design §3); `tell`: the caller didn't do it itself. */
  private emitOrEndEmpty(c: CouncilRecord, tell: boolean): void {
    const empty = this.deps.store.members(c.id).every((m) => !this.alive(c, m.sessionId))
    if (empty && this.deps.store.end(c.id, "empty") && tell && this.deps.isLive(c.callerSessionId)) {
      this.deps.notify(c.callerSessionId, `${c.name} ended: no members left.`)
    }
    this.emit(this.deps.store.get(c.id) ?? c)
  }

  /** Round `round`'s message to member `letter`: its focus and the others' reports from the round before. */
  private roundMessage(c: CouncilRecord, round: number, letter: string, memberId: string): string {
    const focus = this.deps.store.rounds(c.id).find((r) => r.round === round)?.focus ?? ""
    const before = round - 1
    const others = new Map<string, string[]>()
    for (const r of this.deps.store.reports(c.id, before)) if (r.memberSessionId !== memberId) others.set(r.letter, [...(others.get(r.letter) ?? []), r.text])
    const body = others.size === 0
      ? `No other member reported in round ${before}.`
      : [`Reports from round ${before}:`, ...[...others.entries()].sort(([x], [y]) => x.length - y.length || x.localeCompare(y)).map(([l, texts]) => `${l}:\n${texts.join("\n\n")}`)].join("\n\n")
    return `${c.name} · round ${round}. You are ${letter}.\nFocus: ${focus}\n\n${body}`
  }

  private sender(c: CouncilRecord): InboxSender {
    return { name: c.name, id: c.id }
  }

  private emit(c: CouncilRecord): void {
    this.deps.changed(c.callerSessionId, this.view(this.deps.store.get(c.id) ?? c))
  }

  private view(c: CouncilRecord): CouncilView {
    const statuses = this.deps.store.statuses(c.id, c.currentRound)
    const reports = this.deps.store.reports(c.id)
    const rounds = this.deps.store.rounds(c.id)
    const all = this.deps.store.members(c.id)
    const members = all.filter((m) => !m.removed)
    const removed = new Set(all.filter((m) => m.removed).map((m) => m.sessionId))
    return {
      id: c.id,
      name: c.name,
      question: c.question,
      round: c.currentRound,
      ...(rounds.at(-1)?.focus ? { focus: rounds.at(-1)!.focus } : {}),
      status: c.status,
      ...(c.endedBy ? { endedBy: c.endedBy } : {}),
      ...(c.verdict !== undefined ? { verdict: c.verdict } : {}),
      ...(c.endedAt ? { endedAt: c.endedAt } : {}),
      ...(c.callId ? { callId: c.callId } : {}),
      createdAt: c.createdAt,
      // A removed member leaves the card, with its reports.
      members: members.map((m) => {
        const s = this.deps.session(m.sessionId)
        // No session row yet: the member is still starting.
        const status = statuses.get(m.sessionId) ?? (!s || this.deps.isLive(m.sessionId) ? "working" : "killed")
        return {
          letter: m.letter,
          session: m.sessionId,
          name: s?.name ?? m.sessionId,
          agent: (s?.agent ?? "claude") as AgentKind,
          ...(s?.model ? { model: s.model } : {}),
          label: s ? this.deps.label(s) : "",
          status,
        }
      }),
      rounds: rounds.map((r) => ({
        ...r,
        reports: reports.filter((x) => x.round === r.round && !removed.has(x.memberSessionId)).map((x) => ({ id: x.id, letter: x.letter, seq: x.seq, text: x.text, at: x.at })),
      })),
    }
  }
}
