// Council persistence (spec §6). A council is supermux code, not an agent: this is its whole state.
import { randomUUID } from "crypto"
import type { Db } from "../storage/db"

export type MemberRoundStatus = "reported" | "crashed" | "killed" | "failed"

export type CouncilRecord = {
  id: string
  name: string
  callerSessionId: string
  question: string
  currentRound: number
  status: "open"
  callId?: string
  createdAt: string
}
export type CouncilMember = { councilId: string; sessionId: string; letter: string }
export type CouncilRound = { round: number; focus?: string; startedAt: string; completedAt?: string }
export type CouncilReport = { id: string; councilId: string; round: number; memberSessionId: string; letter: string; seq: number; text: string; at: string }

type CouncilRow = { id: string; name: string; caller_session_id: string; question: string; current_round: number; status: string; call_id: string | null; created_at: string }

const toRecord = (r: CouncilRow): CouncilRecord => ({
  id: r.id,
  name: r.name,
  callerSessionId: r.caller_session_id,
  question: r.question,
  currentRound: r.current_round,
  status: "open",
  ...(r.call_id ? { callId: r.call_id } : {}),
  createdAt: r.created_at,
})

export class CouncilStore {
  constructor(private readonly db: Db) {}

  /** A new council, in round 1 (the question), named council-<n>: one more than the highest number in use. */
  create(input: { callerSessionId: string; question: string; callId?: string }, now: Date = new Date()): CouncilRecord {
    const id = randomUUID()
    // Not a row count: a council removed after a failed start would hand its number to a live one.
    // substr(name, 9): the number, after the 8-character `council-` prefix.
    const n = (this.db.query("SELECT COALESCE(MAX(CAST(substr(name, 9) AS INTEGER)), 0) + 1 AS n FROM councils").get() as { n: number }).n
    const at = now.toISOString()
    this.db.transaction(() => {
      this.db.run(
        "INSERT INTO councils (id, name, caller_session_id, question, current_round, status, call_id, created_at) VALUES (?, ?, ?, ?, 1, 'open', ?, ?)",
        [id, `council-${n}`, input.callerSessionId, input.question, input.callId ?? null, at],
      )
      this.db.run("INSERT INTO council_rounds (council_id, round, focus, started_at) VALUES (?, 1, NULL, ?)", [id, at])
    })()
    return this.get(id)!
  }

  get(id: string): CouncilRecord | undefined {
    const row = this.db.query("SELECT * FROM councils WHERE id = ?").get(id) as CouncilRow | null
    return row ? toRecord(row) : undefined
  }

  byCaller(callerSessionId: string): CouncilRecord[] {
    return (this.db.query("SELECT * FROM councils WHERE caller_session_id = ? ORDER BY created_at, rowid").all(callerSessionId) as CouncilRow[]).map(toRecord)
  }

  /** Recorded before the member starts: its preamble and its tool list read it (spec §5). */
  addMember(councilId: string, sessionId: string, letter: string): void {
    this.db.run("INSERT INTO council_members (council_id, session_id, letter) VALUES (?, ?, ?)", [councilId, sessionId, letter])
  }

  members(councilId: string): CouncilMember[] {
    return (this.db.query("SELECT council_id, session_id, letter FROM council_members WHERE council_id = ? ORDER BY length(letter), letter").all(councilId) as Array<{ council_id: string; session_id: string; letter: string }>)
      .map((r) => ({ councilId: r.council_id, sessionId: r.session_id, letter: r.letter }))
  }

  membership(sessionId: string): CouncilMember | undefined {
    const r = this.db.query("SELECT council_id, session_id, letter FROM council_members WHERE session_id = ?").get(sessionId) as { council_id: string; session_id: string; letter: string } | null
    return r ? { councilId: r.council_id, sessionId: r.session_id, letter: r.letter } : undefined
  }

  rounds(councilId: string): CouncilRound[] {
    return (this.db.query("SELECT round, focus, started_at, completed_at FROM council_rounds WHERE council_id = ? ORDER BY round").all(councilId) as Array<{ round: number; focus: string | null; started_at: string; completed_at: string | null }>)
      .map((r) => ({ round: r.round, ...(r.focus !== null ? { focus: r.focus } : {}), startedAt: r.started_at, ...(r.completed_at ? { completedAt: r.completed_at } : {}) }))
  }

  /** Close the current round and open the next with its focus; returns the new round. */
  nextRound(councilId: string, focus: string, now: Date = new Date()): number {
    const at = now.toISOString()
    let round = 0
    this.db.transaction(() => {
      const current = (this.db.query("SELECT current_round FROM councils WHERE id = ?").get(councilId) as { current_round: number } | null)?.current_round
      if (current === undefined) throw new Error(`no such council: ${councilId}`)
      round = current + 1
      this.db.run("UPDATE council_rounds SET completed_at = ? WHERE council_id = ? AND round = ?", [at, councilId, current])
      this.db.run("INSERT INTO council_rounds (council_id, round, focus, started_at) VALUES (?, ?, ?, ?)", [councilId, round, focus, at])
      this.db.run("UPDATE councils SET current_round = ? WHERE id = ?", [round, councilId])
    })()
    return round
  }

  /** One council_report call (spec §6: every call, in order). */
  addReport(councilId: string, round: number, memberSessionId: string, text: string, now: Date = new Date()): CouncilReport {
    const id = randomUUID()
    const at = now.toISOString()
    const seq = (this.db.query("SELECT COALESCE(MAX(seq), 0) + 1 AS seq FROM council_reports WHERE council_id = ?").get(councilId) as { seq: number }).seq
    this.db.run(
      "INSERT INTO council_reports (id, council_id, round, member_session_id, seq, text, at) VALUES (?, ?, ?, ?, ?, ?, ?)",
      [id, councilId, round, memberSessionId, seq, text, at],
    )
    return { id, councilId, round, memberSessionId, letter: this.membership(memberSessionId)?.letter ?? "?", seq, text, at }
  }

  /** Reports in order; one round's, or the whole council's. */
  reports(councilId: string, round?: number): CouncilReport[] {
    const rows = this.db.query(`
      SELECT r.id, r.council_id, r.round, r.member_session_id, r.seq, r.text, r.at, m.letter
        FROM council_reports r LEFT JOIN council_members m ON m.session_id = r.member_session_id
       WHERE r.council_id = ? AND (? IS NULL OR r.round = ?)
       ORDER BY r.seq
    `).all(councilId, round ?? null, round ?? null) as Array<{ id: string; council_id: string; round: number; member_session_id: string; seq: number; text: string; at: string; letter: string | null }>
    return rows.map((r) => ({ id: r.id, councilId: r.council_id, round: r.round, memberSessionId: r.member_session_id, letter: r.letter ?? "?", seq: r.seq, text: r.text, at: r.at }))
  }

  setStatus(councilId: string, round: number, memberSessionId: string, status: MemberRoundStatus, now: Date = new Date()): void {
    this.db.run(
      `INSERT INTO council_member_status (council_id, round, member_session_id, status, at) VALUES (?, ?, ?, ?, ?)
       ON CONFLICT(council_id, round, member_session_id) DO UPDATE SET status = excluded.status, at = excluded.at`,
      [councilId, round, memberSessionId, status, now.toISOString()],
    )
  }

  /** Removes the member's status for the round if it is `status`; true when one was removed. */
  clearStatus(councilId: string, round: number, memberSessionId: string, status: MemberRoundStatus): boolean {
    return this.db.run(
      "DELETE FROM council_member_status WHERE council_id = ? AND round = ? AND member_session_id = ? AND status = ?",
      [councilId, round, memberSessionId, status],
    ).changes > 0
  }

  statuses(councilId: string, round: number): Map<string, MemberRoundStatus> {
    const rows = this.db.query("SELECT member_session_id, status FROM council_member_status WHERE council_id = ? AND round = ?").all(councilId, round) as Array<{ member_session_id: string; status: MemberRoundStatus }>
    return new Map(rows.map((r) => [r.member_session_id, r.status]))
  }

  /**
   * The council row alone: its members' kills then update no council, while their membership still
   * keeps them off the subagent list. remove() deletes the rest.
   */
  removeRecord(councilId: string): void {
    this.db.run("DELETE FROM councils WHERE id = ?", [councilId])
  }

  /** A council that never got its members (a member failed to start): nothing of it stays. */
  remove(councilId: string): void {
    this.db.transaction(() => {
      for (const t of ["council_member_status", "council_reports", "council_rounds", "council_members"]) this.db.run(`DELETE FROM ${t} WHERE council_id = ?`, [councilId])
      this.db.run("DELETE FROM councils WHERE id = ?", [councilId])
    })()
  }
}
