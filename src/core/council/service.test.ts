import { expect, test } from "bun:test"
import { openDb, runMigrations } from "../storage/db"
import { MIGRATIONS } from "../storage/migrations/index"
import { Registry } from "../session-manager/registry"
import { CouncilStore } from "./store"
import { Council, type CouncilView } from "./service"

function setup(over: { busy?: (id: string) => boolean } = {}) {
  const db = openDb(":memory:")
  runMigrations(db, MIGRATIONS)
  const registry = new Registry(db)
  const store = new CouncilStore(db)
  const sent: Array<{ from: string; to: string; text: string; tag?: unknown }> = []
  const notices: Array<{ to: string; text: string }> = []
  const changes: Array<{ caller: string; view: CouncilView }> = []
  const replies = new Map<string, string>()
  const kills: string[] = []
  const council = new Council({
    store,
    session: (id) => registry.sessions.getById(id),
    isLive: (id) => !!registry.get(id),
    label: (s) => (s.agent === "codex" ? "GPT-6" : "Grok"),
    send: (from, to, text, tag) => { sent.push({ from: `${from.name}:${from.id}`, to, text, ...(tag ? { tag } : {}) }) },
    notify: (to, text) => { notices.push({ to, text }) },
    lastReply: (id) => replies.get(id),
    kill: async (id) => { kills.push(id); registry.unregister(id) },
    changed: (caller, view) => { changes.push({ caller, view }) },
    busy: over.busy ?? (() => true),
  })
  const caller = registry.register({ name: "Planner", workdir: "/w", pid: 0, agent: "claude" })
  const c = store.create({ callerSessionId: caller.id, question: "Refresh tokens?" })
  const a = registry.register({ name: "council-1 A", workdir: "/w", pid: 0, agent: "codex", parent_session_id: caller.id })
  const b = registry.register({ name: "council-1 B", workdir: "/w", pid: 0, agent: "grok", parent_session_id: caller.id })
  store.addMember(c.id, a.id, "A")
  store.addMember(c.id, b.id, "B")
  return { registry, store, council, sent, notices, changes, replies, kills, caller, c, a, b }
}

test("opened shows the saved council on the caller's card and sends nothing", () => {
  const t = setup()
  t.council.opened(t.c.id)
  expect(t.sent).toEqual([])
  expect(t.changes.at(-1)?.view.members.map((m) => [m.letter, m.status])).toEqual([["A", "working"], ["B", "working"]])
})

test("a member that started gets only the question, from the council", () => {
  const t = setup()
  t.council.memberStarted(t.a.id)
  t.council.memberStarted(t.b.id)
  expect(t.sent).toEqual([
    { from: `council-1:${t.c.id}`, to: t.a.id, text: "Refresh tokens?\n\nAnswer with council_report({ text })." },
    { from: `council-1:${t.c.id}`, to: t.b.id, text: "Refresh tokens?\n\nAnswer with council_report({ text })." },
  ])
})

test("a member stopped while it was starting gets no question: a message would restore it", () => {
  const t = setup()
  t.registry.unregister(t.b.id)
  t.council.memberStarted(t.b.id)
  expect(t.sent).toEqual([])
})

test("a member still starting (no session yet) shows as working", () => {
  const t = setup()
  t.store.addMember(t.c.id, "m-new", "C")
  t.council.opened(t.c.id)
  expect(t.changes.at(-1)?.view.members.find((m) => m.letter === "C")?.status).toBe("working")
})

test("a member that couldn't start is failed and a live caller hears why", () => {
  const t = setup()
  t.store.addMember(t.c.id, "m-new", "C")
  t.council.memberStartFailed("m-new", "opencode exited at once")
  expect(t.notices).toEqual([{ to: t.caller.id, text: "council-1 · C couldn't start:\nopencode exited at once" }])
  expect(t.changes.at(-1)?.view.members.find((m) => m.letter === "C")?.status).toBe("failed")
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
  expect(t.notices).toEqual([{ to: t.caller.id, text: `council-1 · B [council-1 B:${t.b.id}] was stopped by the user.\nIt sent no message.` }])
  t.notices.length = 0
  t.council.memberArchived(t.registry.get(t.b.id)!, {})
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

test("waiting: the caller waits while a live member of its council has not reported this round", () => {
  const t = setup()
  t.council.opened(t.c.id)
  expect(t.council.waiting(t.caller.id)).toBe(true)
  t.council.report(t.a.id, "A says yes.")
  expect(t.council.waiting(t.caller.id)).toBe(true)
  t.council.report(t.b.id, "B says no.")
  expect(t.council.waiting(t.caller.id)).toBe(false)
  t.council.startRound(t.caller.id, t.c.id, "Go deeper")
  expect(t.council.waiting(t.caller.id)).toBe(true)
  t.council.memberTurnFailed(t.a.id, "boom")
  t.registry.unregister(t.b.id) // killed: archived
  expect(t.council.waiting(t.caller.id)).toBe(false)
  expect(t.council.waiting(t.a.id)).toBe(false)
})

test("waiting: an idle member that never reported does not hold the caller; a busy one does", () => {
  const busy = new Set<string>()
  const t = setup({ busy: (id) => busy.has(id) })
  t.council.opened(t.c.id)
  expect(t.council.waiting(t.caller.id)).toBe(false) // both idle, neither reported
  busy.add(t.a.id)
  expect(t.council.waiting(t.caller.id)).toBe(true)
  t.council.report(t.a.id, "A says yes.")
  expect(t.council.waiting(t.caller.id)).toBe(false) // reported, even while still busy
})

test("nudgeMember: supermux reminds the member to answer the current round", () => {
  const t = setup()
  t.council.startRound(t.caller.id, t.c.id, "Go deeper")
  t.council.nudgeMember(t.a.id)
  expect(t.notices).toEqual([{ to: t.a.id, text: "You haven't answered council-1 round 2 yet. Answer with council_report({ text }). If you can't, say why in council_report." }])
})

test("memberQuiet: the caller hears the member is idle without an answer, with its last message; an archived caller hears nothing", () => {
  const t = setup()
  t.replies.set(t.a.id, "denied: ReadFile")
  t.council.memberQuiet(t.a.id)
  t.council.memberQuiet(t.b.id)
  expect(t.notices).toEqual([
    { to: t.caller.id, text: `council-1 · A [council-1 A:${t.a.id}] is idle and hasn't answered round 1. Its last message:\ndenied: ReadFile` },
    { to: t.caller.id, text: `council-1 · B [council-1 B:${t.b.id}] is idle and hasn't answered round 1. It sent no message.` },
  ])
  t.registry.unregister(t.caller.id)
  t.council.memberQuiet(t.a.id)
  expect(t.notices).toHaveLength(2)
})

// ---- lifecycle (design 2026-10-31) ----------------------------------------------------------------

test("council_end: only the caller, with a verdict; members stopped, no notice, the card shows the verdict", async () => {
  const t = setup()
  await expect(t.council.end(t.c.id, "caller", { callerId: t.a.id, verdict: "x" })).rejects.toThrow("only the council's caller can end it")
  await expect(t.council.end(t.c.id, "caller", { callerId: t.caller.id, verdict: "  " })).rejects.toThrow("verdict must not be empty")
  await t.council.end(t.c.id, "caller", { callerId: t.caller.id, verdict: "Spaces, 2–1." })
  expect(t.kills).toEqual([t.a.id, t.b.id])
  expect(t.notices).toEqual([])
  expect(t.changes.at(-1)!.view).toMatchObject({ status: "ended", endedBy: "caller", verdict: "Spaces, 2–1." })
  await expect(t.council.end(t.c.id, "caller", { callerId: t.caller.id, verdict: "again" })).rejects.toThrow("council-1 has already ended")
})

test("Stop council (the user): one notice to the caller, members stopped quietly", async () => {
  const t = setup()
  await t.council.end(t.c.id, "user")
  for (const id of t.kills) t.council.memberArchived(t.registry.sessions.getById(id)!, { endedBy: "parent" })
  expect(t.notices).toEqual([{ to: t.caller.id, text: "council-1 was ended by the user." }])
  expect(t.changes.at(-1)!.view).toMatchObject({ status: "ended", endedBy: "user" })
  expect(t.changes.at(-1)!.view.verdict).toBeUndefined()
})

test("an ended council is never touched again: no report, round, nudge or notice", async () => {
  const t = setup()
  await t.council.end(t.c.id, "caller", { callerId: t.caller.id, verdict: "done" })
  expect(() => t.council.report(t.a.id, "late")).toThrow("council-1 has ended")
  expect(() => t.council.startRound(t.caller.id, t.c.id, "more")).toThrow("council-1 has ended")
  t.council.nudgeMember(t.a.id)
  t.council.memberQuiet(t.a.id)
  t.council.memberTurnFailed(t.a.id, "boom")
  t.council.memberCrashed(t.a.id)
  expect(t.notices).toEqual([])
  expect(t.council.watched(t.a.id)).toBe(false)
  expect(t.council.waiting(t.caller.id)).toBe(false)
})

test("a member that comes up after the council ended is stopped, not sent the question", async () => {
  const t = setup()
  t.store.addMember(t.c.id, "m-late", "C")
  await t.council.end(t.c.id, "caller", { callerId: t.caller.id, verdict: "done" })
  t.registry.register({ id: "m-late", name: "council-1 C", workdir: "/w", pid: 0, agent: "codex", parent_session_id: t.caller.id })
  t.kills.length = 0
  t.council.memberStarted("m-late")
  await Promise.resolve()
  expect(t.kills).toEqual(["m-late"])
  expect(t.sent).toEqual([])
})

test("council_remove: stops the members by letter, no notice; unknown or gone letters are refused", async () => {
  const t = setup()
  await expect(t.council.remove(t.caller.id, t.c.id, ["Z"])).rejects.toThrow("council-1 has no member Z")
  expect(await t.council.remove(t.caller.id, t.c.id, ["B"])).toEqual({ removed: ["B"] })
  t.council.memberArchived(t.registry.sessions.getById(t.b.id)!, { endedBy: "parent" })
  expect(t.kills).toEqual([t.b.id])
  expect(t.notices).toEqual([])
  await expect(t.council.remove(t.caller.id, t.c.id, ["B"])).rejects.toThrow("council-1 · B is not running")
  await expect(t.council.remove(t.a.id, t.c.id, ["A"])).rejects.toThrow("only the council's caller can remove members")
})

test("the last member gone ends the council; the caller hears it unless it removed them itself", async () => {
  const t = setup()
  t.registry.unregister(t.a.id)
  t.council.memberArchived(t.registry.sessions.getById(t.a.id)!, { endedBy: "client" })
  expect(t.changes.at(-1)!.view.status).toBe("open")
  t.notices.length = 0
  t.registry.unregister(t.b.id)
  t.council.memberArchived(t.registry.sessions.getById(t.b.id)!, { endedBy: "client" })
  expect(t.notices.map((n) => n.text)).toEqual([`council-1 · B [council-1 B:${t.b.id}] was stopped by the user.\nIt sent no message.`, "council-1 ended: no members left."])
  expect(t.changes.at(-1)!.view).toMatchObject({ status: "ended", endedBy: "empty" })

  const u = setup()
  await u.council.remove(u.caller.id, u.c.id, ["A", "B"])
  for (const id of u.kills) u.council.memberArchived(u.registry.sessions.getById(id)!, { endedBy: "parent" })
  expect(u.notices).toEqual([])
  expect(u.store.get(u.c.id)).toMatchObject({ status: "ended", endedBy: "empty" })
})

test("a member still starting keeps the council open; one whose start failed doesn't", () => {
  const t = setup()
  t.store.addMember(t.c.id, "m-new", "C")
  t.registry.unregister(t.a.id); t.registry.unregister(t.b.id)
  t.council.memberArchived(t.registry.sessions.getById(t.b.id)!, { endedBy: "parent" })
  expect(t.store.get(t.c.id)!.status).toBe("open")
  t.council.memberStartFailed("m-new", "exited")
  expect(t.store.get(t.c.id)).toMatchObject({ status: "ended", endedBy: "empty" })
  expect(t.notices.map((n) => n.text)).toEqual(["council-1 · C couldn't start:\nexited", "council-1 ended: no members left."])
})

test("the caller being killed (cascade) ends its council without a word", () => {
  const t = setup()
  t.registry.unregister(t.a.id)
  t.council.memberArchived(t.registry.sessions.getById(t.a.id)!, { cascade: true })
  expect(t.store.get(t.c.id)).toMatchObject({ status: "ended", endedBy: "empty" })
  expect(t.notices).toEqual([])
})

test("a member added in round 2 gets the question, the round's focus and round 1's reports", () => {
  const t = setup()
  t.council.report(t.a.id, "A says yes.")
  t.council.startRound(t.caller.id, t.c.id, "Go deeper")
  t.sent.length = 0
  t.store.addMember(t.c.id, "m-new", "C")
  t.registry.register({ id: "m-new", name: "council-1 C", workdir: "/w", pid: 0, agent: "codex", parent_session_id: t.caller.id })
  t.council.memberStarted("m-new")
  expect(t.sent).toEqual([{
    from: `council-1:${t.c.id}`, to: "m-new",
    text: "Refresh tokens?\n\ncouncil-1 · round 2. You are C.\nFocus: Go deeper\n\nReports from round 1:\n\nA:\nA says yes.\n\nAnswer with council_report({ text }).",
  }])
})
