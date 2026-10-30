import { expect, test } from "bun:test"
import { openDb, runMigrations } from "../storage/db"
import { MIGRATIONS } from "../storage/migrations/index"
import { Registry } from "../session-manager/registry"
import { CouncilStore } from "./store"
import { Council, type CouncilView } from "./service"

function setup() {
  const db = openDb(":memory:")
  runMigrations(db, MIGRATIONS)
  const registry = new Registry(db)
  const store = new CouncilStore(db)
  const sent: Array<{ from: string; to: string; text: string; tag?: unknown }> = []
  const notices: Array<{ to: string; text: string }> = []
  const changes: Array<{ caller: string; view: CouncilView }> = []
  const replies = new Map<string, string>()
  const council = new Council({
    store,
    session: (id) => registry.sessions.getById(id),
    isLive: (id) => !!registry.get(id),
    label: (s) => (s.agent === "codex" ? "GPT-6" : "Grok"),
    send: (from, to, text, tag) => { sent.push({ from: `${from.name}:${from.id}`, to, text, ...(tag ? { tag } : {}) }) },
    notify: (to, text) => { notices.push({ to, text }) },
    lastReply: (id) => replies.get(id),
    changed: (caller, view) => { changes.push({ caller, view }) },
  })
  const caller = registry.register({ name: "Planner", workdir: "/w", pid: 0, agent: "claude" })
  const c = store.create({ callerSessionId: caller.id, question: "Refresh tokens?" })
  const a = registry.register({ name: "council-1 A", workdir: "/w", pid: 0, agent: "codex", parent_session_id: caller.id })
  const b = registry.register({ name: "council-1 B", workdir: "/w", pid: 0, agent: "grok", parent_session_id: caller.id })
  store.addMember(c.id, a.id, "A")
  store.addMember(c.id, b.id, "B")
  return { registry, store, council, sent, notices, changes, replies, caller, c, a, b }
}

test("opened sends each member only the question, from the council", () => {
  const t = setup()
  t.council.opened(t.c.id)
  expect(t.sent).toEqual([
    { from: `council-1:${t.c.id}`, to: t.a.id, text: "Refresh tokens?\n\nAnswer with council_report({ text })." },
    { from: `council-1:${t.c.id}`, to: t.b.id, text: "Refresh tokens?\n\nAnswer with council_report({ text })." },
  ])
  expect(t.changes.at(-1)?.view.members.map((m) => [m.letter, m.status])).toEqual([["A", "working"], ["B", "working"]])
})

test("opened sends the question only to live members: a message would restore an archived one", () => {
  const t = setup()
  t.registry.unregister(t.b.id)
  t.council.opened(t.c.id)
  expect(t.sent).toEqual([{ from: `council-1:${t.c.id}`, to: t.a.id, text: "Refresh tokens?\n\nAnswer with council_report({ text })." }])
})

test("a report is recorded and forwarded to the caller at once, with the member's name and the council tag", () => {
  const t = setup()
  const r = t.council.report(t.b.id, "Yes, with rotation.")
  expect(r.id).toBeTruthy()
  expect(t.sent).toEqual([{
    from: `council-1:${t.c.id}`, to: t.caller.id,
    text: `council-1 · round 1 · B — Grok [council-1 B:${t.b.id}]:\nYes, with rotation.`,
    tag: { id: t.c.id, round: 1, letter: "B" },
  }])
  const view = t.changes.at(-1)!.view
  expect(view.members.find((m) => m.letter === "B")?.status).toBe("reported")
  expect(view.rounds[0]!.reports.map((x) => [x.letter, x.text])).toEqual([["B", "Yes, with rotation."]])
})

test("only a council member can report", () => {
  const t = setup()
  expect(() => t.council.report(t.caller.id, "x")).toThrow("only council members can report")
})

test("a round sends each member the focus and the others' reports by letter, in order; only the caller can start it", () => {
  const t = setup()
  t.council.report(t.a.id, "A1")
  t.council.report(t.b.id, "B1")
  t.council.report(t.a.id, "A2")
  t.sent.length = 0
  expect(() => t.council.startRound(t.a.id, t.c.id, "x")).toThrow("only the council's caller can start a round")
  expect(t.council.startRound(t.caller.id, t.c.id, "Expiry")).toEqual({ round: 2 })
  expect(t.sent).toEqual([
    { from: `council-1:${t.c.id}`, to: t.a.id, text: "council-1 · round 2. You are A.\nFocus: Expiry\n\nReports from round 1:\n\nB:\nB1\n\nAnswer with council_report({ text })." },
    { from: `council-1:${t.c.id}`, to: t.b.id, text: "council-1 · round 2. You are B.\nFocus: Expiry\n\nReports from round 1:\n\nA:\nA1\n\nA2\n\nAnswer with council_report({ text })." },
  ])
  expect(t.changes.at(-1)!.view).toMatchObject({ round: 2, focus: "Expiry" })
  expect(t.changes.at(-1)!.view.members.map((m) => m.status)).toEqual(["working", "working"])
})

test("a member with nothing to read is told so; an archived member gets no round message", () => {
  const t = setup()
  t.registry.unregister(t.b.id)
  t.council.startRound(t.caller.id, t.c.id, "Next")
  expect(t.sent).toEqual([{ from: `council-1:${t.c.id}`, to: t.a.id, text: "council-1 · round 2. You are A.\nFocus: Next\n\nNo other member reported in round 1.\n\nAnswer with council_report({ text })." }])
  expect(t.changes.at(-1)!.view.members.find((m) => m.letter === "B")?.status).toBe("killed")
})

test("a crash or a kill sets the member's status and tells a live caller; not for the cascade, nor when the caller killed it", () => {
  const t = setup()
  t.replies.set(t.a.id, "half way")
  t.council.memberCrashed(t.a.id)
  expect(t.notices).toEqual([{ to: t.caller.id, text: `council-1 · A [council-1 A:${t.a.id}] crashed.\nIts last message:\nhalf way` }])
  expect(t.changes.at(-1)!.view.members.find((m) => m.letter === "A")?.status).toBe("crashed")
  t.notices.length = 0
  t.council.memberArchived(t.registry.get(t.b.id)!, { endedBy: "client" })
  expect(t.notices).toEqual([{ to: t.caller.id, text: `council-1 · B [council-1 B:${t.b.id}] was killed.\nIt sent no message.` }])
  t.notices.length = 0
  t.council.memberArchived(t.registry.get(t.b.id)!, { endedBy: "parent" })
  t.council.memberArchived(t.registry.get(t.b.id)!, { cascade: true })
  expect(t.notices).toEqual([])
})

test("forCaller lists the caller's councils with members (who's who) and rounds", () => {
  const t = setup()
  const [view] = t.council.forCaller(t.caller.id)
  expect(view).toMatchObject({ id: t.c.id, name: "council-1", question: "Refresh tokens?", round: 1, status: "open" })
  expect(view!.members).toEqual([
    { letter: "A", session: t.a.id, name: "council-1 A", agent: "codex", label: "GPT-6", status: "working" },
    { letter: "B", session: t.b.id, name: "council-1 B", agent: "grok", label: "Grok", status: "working" },
  ])
})

test("events for a session that is not a member change nothing", () => {
  const t = setup()
  t.council.memberCrashed(t.caller.id)
  t.council.memberArchived(t.registry.get(t.caller.id)!, {})
  expect(t.notices).toEqual([])
  expect(t.changes).toEqual([])
})

test("a failed turn sets the member's status and tells a live caller; a report already made this round stands", () => {
  const t = setup()
  const error = "API error (status 402 Payment Required): Grok Build usage balance exhausted"
  t.council.memberTurnFailed(t.b.id, error)
  expect(t.notices).toEqual([{ to: t.caller.id, text: `council-1 · B [council-1 B:${t.b.id}] stopped with an error:\n${error}` }])
  expect(t.changes.at(-1)!.view.members.find((m) => m.letter === "B")?.status).toBe("failed")
  t.council.report(t.a.id, "A1")
  t.council.memberTurnFailed(t.a.id, "boom")
  expect(t.changes.at(-1)!.view.members.find((m) => m.letter === "A")?.status).toBe("reported")
  expect(t.notices.at(-1)).toEqual({ to: t.caller.id, text: `council-1 · A [council-1 A:${t.a.id}] stopped with an error:\nboom` })
  // An archived member's late failure and a non-member's change nothing.
  t.notices.length = 0
  t.registry.unregister(t.b.id)
  t.council.memberTurnFailed(t.b.id, "late")
  t.council.memberTurnFailed(t.caller.id, "x")
  expect(t.notices).toEqual([])
})

test("a failed turn reported after a newer turn started (status: false) keeps the member's status, and still tells the caller", () => {
  const t = setup()
  t.council.memberTurnFailed(t.b.id, "boom", { status: false })
  expect(t.council.forCaller(t.caller.id)[0]!.members.find((m) => m.letter === "B")?.status).toBe("working")
  expect(t.notices).toEqual([{ to: t.caller.id, text: `council-1 · B [council-1 B:${t.b.id}] stopped with an error:\nboom` }])
})

test("a member's next turn clears its failed status for the round, never a report", () => {
  const t = setup()
  t.council.memberTurnFailed(t.b.id, "boom")
  t.council.report(t.a.id, "A1")
  const changes = t.changes.length
  t.council.memberTurnStarted(t.b.id)
  t.council.memberTurnStarted(t.a.id)
  const members = t.council.forCaller(t.caller.id)[0]!.members
  expect(members.find((m) => m.letter === "B")?.status).toBe("working")
  expect(members.find((m) => m.letter === "A")?.status).toBe("reported")
  // Only the cleared failure changed the card; a non-member's turn changes nothing.
  expect(t.changes.length).toBe(changes + 1)
  t.council.memberTurnStarted(t.caller.id)
  t.council.memberTurnStarted(t.b.id)
  expect(t.changes.length).toBe(changes + 1)
})

test("an archived caller hears nothing of a member's failed turn: a message would restore it", () => {
  const t = setup()
  t.registry.unregister(t.caller.id)
  t.council.memberTurnFailed(t.b.id, "boom")
  expect(t.notices).toEqual([])
})
