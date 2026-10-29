// A council (spec 2026-10-28 §5): supermux code, not an agent. It sends the members the question
// and each round's focus with the others' reports (by letter only), forwards every report to the
// caller as it arrives (with the member's name), and tells the caller when a member crashes or is
// killed. The caller decides when a round is over.
import type { AgentKind } from "../../shared/agents"
import type { Session } from "../session-manager/types"
import type { InboxCouncilTag, InboxSender } from "../inbox/store"
import type { CouncilRecord, CouncilStore, MemberRoundStatus } from "./store"

export type CouncilView = {
  id: string
  name: string
  question: string
  round: number
  focus?: string
  status: "open"
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
  /** The caller's council card changed (snapshot `councils`, `council_update`). */
  changed(callerSessionId: string, view: CouncilView): void
}

/**
 * Ends every message the council sends a member (decided 2026-10-29): a member that answered in its own
 * chat instead of calling council_report left its caller without the answer.
 */
const REPORT_REMINDER = "\n\nAnswer with council_report({ text })."

export class Council {
  constructor(private readonly deps: CouncilDeps) {}

  /** Round 1: each member's only starting message is the question, from the council (spec §5). */
  opened(councilId: string): void {
    const c = this.deps.store.get(councilId)
    if (!c) return
    for (const m of this.deps.store.members(c.id)) {
      // A killed member is archived; a message would restore it.
      if (this.deps.isLive(m.sessionId)) this.deps.send(this.sender(c), m.sessionId, `${c.question}${REPORT_REMINDER}`)
    }
    this.emit(c)
  }

  /** council_report: the member's answer for the current round, forwarded to the caller at once. */
  report(memberId: string, text: string): { id: string } {
    const membership = this.deps.store.membership(memberId)
    const c = membership && this.deps.store.get(membership.councilId)
    if (!membership || !c) throw new Error("only council members can report")
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
    const c = this.deps.store.get(councilId)
    if (!c) throw new Error(`unknown council: ${councilId}`)
    if (c.callerSessionId !== callerId) throw new Error("only the council's caller can start a round")
    const ended = c.currentRound
    const reports = this.deps.store.reports(c.id, ended)
    const round = this.deps.store.nextRound(c.id, focus)
    for (const m of this.deps.store.members(c.id)) {
      // A killed member is archived; a message would restore it.
      if (!this.deps.isLive(m.sessionId)) continue
      const others = new Map<string, string[]>()
      for (const r of reports) if (r.memberSessionId !== m.sessionId) others.set(r.letter, [...(others.get(r.letter) ?? []), r.text])
      const body = others.size === 0
        ? `No other member reported in round ${ended}.`
        : [`Reports from round ${ended}:`, ...[...others.entries()].sort(([x], [y]) => x.length - y.length || x.localeCompare(y)).map(([letter, texts]) => `${letter}:\n${texts.join("\n\n")}`)].join("\n\n")
      this.deps.send(this.sender(c), m.sessionId, `${c.name} · round ${round}. You are ${m.letter}.\nFocus: ${focus}\n\n${body}${REPORT_REMINDER}`)
    }
    this.emit(this.deps.store.get(c.id)!)
    return { round }
  }

  /** The member's core session failed. */
  memberCrashed(memberId: string): void {
    this.ended(memberId, "crashed")
  }

  /**
   * The member was archived. The cascade (the caller being killed) changes nothing, and a caller that
   * killed it with kill_session already has the answer (decision #15).
   */
  memberArchived(member: Session, how: { cascade?: boolean; endedBy?: "parent" | "client" }): void {
    if (how.cascade) return
    this.ended(member.id, "killed", how.endedBy === "parent")
  }

  forCaller(callerSessionId: string): CouncilView[] {
    return this.deps.store.byCaller(callerSessionId).map((c) => this.view(c))
  }

  private ended(memberId: string, status: "crashed" | "killed", quiet = false): void {
    const membership = this.deps.store.membership(memberId)
    const c = membership && this.deps.store.get(membership.councilId)
    const member = this.deps.session(memberId)
    if (!membership || !c || !member) return
    this.deps.store.setStatus(c.id, c.currentRound, memberId, status)
    if (!quiet && this.deps.isLive(c.callerSessionId)) {
      const last = this.deps.lastReply(memberId)
      const what = status === "crashed" ? "crashed" : "was killed"
      this.deps.notify(c.callerSessionId, [`${c.name} · ${membership.letter} [${member.name}:${member.id}] ${what}.`, last ? `Its last message:\n${last}` : "It sent no message."].join("\n"))
    }
    this.emit(c)
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
    return {
      id: c.id,
      name: c.name,
      question: c.question,
      round: c.currentRound,
      ...(rounds.at(-1)?.focus ? { focus: rounds.at(-1)!.focus } : {}),
      status: c.status,
      ...(c.callId ? { callId: c.callId } : {}),
      createdAt: c.createdAt,
      members: this.deps.store.members(c.id).map((m) => {
        const s = this.deps.session(m.sessionId)
        const status = statuses.get(m.sessionId) ?? (this.deps.isLive(m.sessionId) ? "working" : "killed")
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
        reports: reports.filter((x) => x.round === r.round).map((x) => ({ id: x.id, letter: x.letter, seq: x.seq, text: x.text, at: x.at })),
      })),
    }
  }
}
