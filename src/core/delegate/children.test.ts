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
