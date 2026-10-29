import { expect, test } from "bun:test"
import { Registry } from "../session-manager/registry"
import { SubagentStore } from "../session-manager/subagent-store"
import { DelegatedChildren } from "./children"

function setup() {
  const registry = new Registry()
  const subagents = new SubagentStore()
  const notices: Array<{ to: string; text: string }> = []
  const replies = new Map<string, string>()
  const parent = registry.register({ name: "Fix login", workdir: "/w", pid: 0, agent: "claude" })
  const child = registry.register({ name: "Review auth", workdir: "/w", pid: 0, agent: "codex", parent_session_id: parent.id })
  const children = new DelegatedChildren({
    session: (id) => registry.sessions.getById(id),
    isLive: (id) => !!registry.get(id),
    subagents,
    lastReply: (id) => replies.get(id),
    describe: (s) => `${s.agent} · gpt-6`,
    notify: (to, text) => { notices.push({ to, text }) },
  })
  return { registry, subagents, notices, replies, parent, child: registry.get(child.id)!, children }
}

test("started puts the child's card in the parent's subagent list", () => {
  const t = setup()
  t.children.started(t.child, "Review the auth changes", "toolu_1")
  expect(t.subagents.get(t.parent.id)).toEqual([expect.objectContaining({
    id: t.child.id, name: "Review auth", description: "codex · gpt-6", prompt: "Review the auth changes", status: "running",
    messaging: "direct", canMessage: true, canStop: true, actionsSource: "derived", parentCallId: "toolu_1",
  })])
  expect(t.children.isChild(t.parent.id, t.child.id)).toBe(true)
  expect(t.children.isChild(t.parent.id, "native-sub")).toBe(false)
})

test("turns, replies and activity follow the child; a message wakes a completed card", () => {
  const t = setup()
  t.children.started(t.child, "task")
  t.children.activity(t.child.id, "Bash")
  expect(t.subagents.find(t.parent.id, t.child.id)?.activity).toBe("Bash")
  t.children.replied(t.child.id, "done: 3 issues")
  t.children.turnCompleted(t.child.id)
  expect(t.subagents.find(t.parent.id, t.child.id)).toMatchObject({ status: "completed", result: "done: 3 issues" })
  t.children.turnStarted(t.child.id)
  expect(t.subagents.find(t.parent.id, t.child.id)).toMatchObject({ status: "running", canStop: true })
})

test("a crash fails the card and tells a live parent, with the child's last message", () => {
  const t = setup()
  t.children.started(t.child, "task")
  t.replies.set(t.child.id, "half way")
  t.children.crashed(t.child.id)
  expect(t.subagents.find(t.parent.id, t.child.id)?.status).toBe("failed")
  expect(t.notices).toEqual([{ to: t.parent.id, text: `Your subagent [Review auth:${t.child.id}] crashed.\nIts last message:\nhalf way` }])
})

test("a crash that comes after its turn ended (the adapter's order) leaves the card failed", () => {
  const t = setup()
  t.children.started(t.child, "task")
  t.children.turnCompleted(t.child.id)
  t.children.crashed(t.child.id)
  expect(t.subagents.find(t.parent.id, t.child.id)?.status).toBe("failed")
})

test("a kill cancels the card and tells the parent; the cascade and an archived parent hear nothing", () => {
  const t = setup()
  t.children.started(t.child, "task")
  t.children.archived(t.child, { endedBy: "client" })
  expect(t.subagents.find(t.parent.id, t.child.id)).toMatchObject({ status: "cancelled", endedBy: "client", canStop: false })
  expect(t.notices).toEqual([{ to: t.parent.id, text: `Your subagent [Review auth:${t.child.id}] was killed.\nIt sent no message.` }])
  t.notices.length = 0
  t.children.archived(t.child, { cascade: true })
  expect(t.notices).toEqual([])
  t.registry.unregister(t.parent.id)
  t.children.archived(t.child, {})
  t.children.crashed(t.child.id)
  expect(t.notices).toEqual([])
})

test("a session with no parent is ignored", () => {
  const t = setup()
  t.children.turnStarted(t.parent.id)
  t.children.crashed(t.parent.id)
  t.children.archived(t.registry.get(t.parent.id)!, {})
  expect(t.subagents.get(t.parent.id)).toEqual([])
  expect(t.notices).toEqual([])
})

test("an archived child's late turn, reply, activity and crash change nothing and send no notice", () => {
  const t = setup()
  t.children.started(t.child, "task")
  t.registry.unregister(t.child.id)
  t.children.archived(t.registry.sessions.getById(t.child.id)!, { endedBy: "client" })
  t.notices.length = 0
  const card = t.subagents.find(t.parent.id, t.child.id)
  t.children.turnStarted(t.child.id)
  t.children.replied(t.child.id, "late")
  t.children.activity(t.child.id, "Bash")
  t.children.crashed(t.child.id)
  expect(t.subagents.find(t.parent.id, t.child.id)).toEqual(card)
  expect(t.notices).toEqual([])
})

test("a card that is gone (evicted; here a fresh store) is recreated whole by a kill or a crash", () => {
  const killed = setup()
  killed.children.archived(killed.child, { endedBy: "client" })
  expect(killed.subagents.find(killed.parent.id, killed.child.id)).toMatchObject({
    name: "Review auth", description: "codex · gpt-6", status: "cancelled", endedBy: "client", canStop: false, cannotStopReason: "It was killed",
  })
  const crashed = setup()
  crashed.children.crashed(crashed.child.id)
  expect(crashed.subagents.find(crashed.parent.id, crashed.child.id)).toMatchObject({ name: "Review auth", description: "codex · gpt-6", status: "failed" })
})

test("a kill by its own parent (kill_session) cancels the card but sends no notice: the tool's result already told it", () => {
  const t = setup()
  t.children.started(t.child, "task")
  t.children.archived(t.child, { endedBy: "parent" })
  expect(t.subagents.find(t.parent.id, t.child.id)).toMatchObject({ status: "cancelled", endedBy: "parent", canStop: false })
  expect(t.notices).toEqual([])
})
