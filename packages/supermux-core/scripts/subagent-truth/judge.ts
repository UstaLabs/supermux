/**
 * Turns what a live run observed into per-cell pass/fail against the matrix. Pure (unit-tested).
 */
import { STATES, liveCell, rowFor, type ActionExpect, type AgentId, type StateId, type TerminalStateId } from "./matrix.js"

export type Snapshot = {
  status?: string
  endedBy?: string
  canMessage?: boolean
  canStop?: boolean
  cannotMessageReason?: string
  cannotStopReason?: string
  actionsSource?: string
  messaging?: string
}

export type ActionOutcome =
  | {
      ok: true
      via?: string
      /** Settled delivery (`delivered` | `refused` | `unconfirmed`), or "timeout". */
      delivery?: string
      deliveryReason?: string
      /** The subagent ran again after the call (a `resumed` phase, then a terminal one). */
      reran?: boolean
      /** Terminal phase / endedBy the subagent reached after a stop. */
      terminal?: string
      endedBy?: string
      /** Soft evidence: the child's answer contained the requested keyword. */
      answered?: boolean
    }
  | { ok: false; code?: string; message: string }

export type Observation =
  | { kind: "status"; state: StateId; resumeOf?: TerminalStateId; subagentId: string; snapshot?: Snapshot }
  | { kind: "message" | "stop"; state: StateId; resumeOf?: TerminalStateId; subagentId: string; before?: Snapshot; outcome: ActionOutcome }
  /** A state the run could not reach (the model did not spawn / end the subagent as asked). */
  | { kind: "unreached"; state: StateId; resumeOf?: TerminalStateId; reason: string }

export type CellCheck = {
  state: StateId
  /** For "after resume": the state the subagent was in before the restart. */
  resumeOf?: TerminalStateId
  check: "status" | "message" | "stop" | "reached"
  subagentId?: string
  expected: string
  actual: string
  pass: boolean
  note?: string
}

const fmtExpect = (e: ActionExpect) => e.ok ? `offered${e.via ? ` (${e.via})` : ""} and works` : `refused: "${e.reason}"`

function fmtSnapshot(s: Snapshot | undefined): string {
  if (!s) return "no snapshot"
  return [s.status, s.endedBy && `endedBy=${s.endedBy}`, s.actionsSource && `source=${s.actionsSource}`].filter(Boolean).join(" ")
}

function judgeAction(action: "message" | "stop", expect: ActionExpect, obs: Extract<Observation, { kind: "message" | "stop" }>): { pass: boolean; actual: string; note?: string } {
  const offered = action === "message" ? obs.before?.canMessage : obs.before?.canStop
  const reason = action === "message" ? obs.before?.cannotMessageReason : obs.before?.cannotStopReason
  const flags = obs.before ? `flags ${offered ? "on" : `off ("${reason ?? ""}")`}` : "no flags"
  const o = obs.outcome
  const problems: string[] = []
  if (expect.ok) {
    if (offered !== true) problems.push("library did not offer it")
    if (!o.ok) problems.push(`call failed: ${o.code ?? "error"} "${o.message}"`)
    else if (action === "message") {
      if (expect.via && o.via !== expect.via) problems.push(`via ${o.via} (expected ${expect.via})`)
      if (o.delivery !== "delivered") problems.push(`delivery ${o.delivery ?? "none"}${o.deliveryReason ? ` (${o.deliveryReason})` : ""}`)
      if (obs.state !== "running" && o.reran !== true) problems.push("the subagent did not run again")
    } else if (o.terminal !== "cancelled" || o.endedBy !== "client") problems.push(`ended ${o.terminal ?? "never"}${o.endedBy ? ` by ${o.endedBy}` : ""} (expected cancelled by client)`)
    const actual = o.ok
      ? `${flags}; ${action === "message" ? `via ${o.via}, delivery ${o.delivery}${o.reran ? ", reran" : ""}` : `ended ${o.terminal ?? "?"} by ${o.endedBy ?? "?"}`}`
      : `${flags}; ${o.code ?? "error"}: ${o.message}`
    const note = action === "message" && o.ok && o.answered === false ? "child answer did not contain the keyword (soft check)" : undefined
    return { pass: problems.length === 0, actual: problems.length ? `${actual} — ${problems.join("; ")}` : actual, ...(note ? { note } : {}) }
  }
  if (offered !== false) problems.push("library offered it")
  else if (reason !== expect.reason) problems.push(`flag reason "${reason ?? ""}"`)
  if (o.ok) problems.push("call succeeded")
  else {
    if (o.code !== "subagent_unavailable") problems.push(`code ${o.code ?? "none"}`)
    if (o.message !== expect.reason) problems.push(`refusal "${o.message}"`)
  }
  const actual = o.ok ? `${flags}; succeeded` : `${flags}; ${o.code}: "${o.message}"`
  return { pass: problems.length === 0, actual: problems.length ? `${actual} — ${problems.join("; ")}` : actual }
}

/**
 * Every cell of `agent`'s row that has a live expectation becomes checks: status (+ endedBy and
 * actionsSource), message and stop. "after resume" is checked per subagent against the state it
 * was in before the restart. A missing observation fails its check.
 */
export function judge(agent: AgentId, observations: Observation[]): CellCheck[] {
  const row = rowFor(agent)
  const out: CellCheck[] = []
  const find = <K extends Observation["kind"]>(kind: K, state: StateId, resumeOf?: TerminalStateId) =>
    observations.filter((o): o is Extract<Observation, { kind: K }> => o.kind === kind && o.state === state && o.resumeOf === resumeOf)
  const unreached = (state: StateId, resumeOf?: TerminalStateId) => find("unreached", state, resumeOf)[0]
  const check = (state: StateId, resumeOf: TerminalStateId | undefined) => {
    const cell = liveCell(agent, resumeOf ?? state)
    if (!cell) return
    const gap = unreached(state, resumeOf)
    if (gap) { out.push({ state, ...(resumeOf ? { resumeOf } : {}), check: "reached", expected: "state reached", actual: gap.reason, pass: false }); return }
    const expectedStatus = [cell.status, cell.endedBy && `endedBy=${cell.endedBy}`, row.actionsSource && `source=${row.actionsSource}`].filter(Boolean).join(" ")
    const statuses = find("status", state, resumeOf)
    if (!statuses.length) out.push({ state, ...(resumeOf ? { resumeOf } : {}), check: "status", expected: expectedStatus, actual: "not observed", pass: false })
    for (const s of statuses) {
      const snap = s.snapshot
      const pass = !!snap && snap.status === cell.status && (snap.endedBy ?? undefined) === cell.endedBy && (!row.actionsSource || snap.actionsSource === row.actionsSource)
      out.push({ state, ...(resumeOf ? { resumeOf } : {}), check: "status", subagentId: s.subagentId, expected: expectedStatus, actual: fmtSnapshot(snap), pass })
    }
    for (const action of ["message", "stop"] as const) {
      const expect = cell[action]
      const attempts = find(action, state, resumeOf)
      if (!attempts.length) { out.push({ state, ...(resumeOf ? { resumeOf } : {}), check: action, expected: fmtExpect(expect), actual: "not attempted", pass: false }); continue }
      for (const attempt of attempts) {
        const r = judgeAction(action, expect, attempt)
        out.push({ state, ...(resumeOf ? { resumeOf } : {}), check: action, subagentId: attempt.subagentId, expected: fmtExpect(expect), actual: r.actual, pass: r.pass, ...(r.note ? { note: r.note } : {}) })
      }
    }
  }
  for (const state of STATES) {
    if (state === "afterResume") continue
    check(state, undefined)
  }
  // After resume: each subagent against the state it ended the first process in.
  const resumed = new Set(observations.filter(o => o.state === "afterResume" && o.resumeOf).map(o => o.resumeOf!))
  if (!resumed.size && !unreached("afterResume")) out.push({ state: "afterResume", check: "reached", expected: "subagents checked after a restart", actual: "no observations", pass: false })
  const gap = unreached("afterResume")
  if (gap) out.push({ state: "afterResume", check: "reached", expected: "state reached", actual: gap.reason, pass: false })
  for (const resumeOf of resumed) check("afterResume", resumeOf)
  return out
}
