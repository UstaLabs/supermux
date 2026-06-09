import { test, expect } from "bun:test"
import { ViewingTracker } from "../src/core/push/viewing-tracker"
import { firePushForReply } from "../src/core/push/hook"
import type { PushSender } from "../src/core/push/sender"

function fakeSender(sent: string[]): PushSender {
  return {
    sendToChat: async () => ({ ok: true }),
    sendToDevice: async (device) => {
      sent.push(device)
      return { ok: true }
    },
  }
}

const reply = (text = "hi") => ({ op: "reply", chat_id: "web", text }) as any

const fire = (vt: ViewingTracker, sent: string[], devices: string[], isMuted = () => false) =>
  firePushForReply({
    sender: fakeSender(sent),
    action: reply(),
    sessionName: "s",
    sessionId: "S",
    isMuted,
    devices: () => devices,
    anyPresent: (sid) => vt.isAnyPresentFor(sid),
  })

test("GLOBAL suppress: if ANY device is present for the session, NO device is notified", async () => {
  const vt = new ViewingTracker()
  vt.update("phone", { session: "S", visible: true }) // present for S on the phone
  vt.update("tablet", { session: null, visible: false }) // backgrounded
  const sent: string[] = []
  await fire(vt, sent, ["phone", "tablet", "laptop"])
  expect(sent).toEqual([]) // user is looking at it on the phone → no device buzzes
})

test("GLOBAL suppress also triggers when any device is on the chat list", async () => {
  const vt = new ViewingTracker()
  vt.update("tablet", { session: null, visible: true }) // on the list
  const sent: string[] = []
  await fire(vt, sent, ["phone", "tablet"])
  expect(sent).toEqual([])
})

test("no device present (or only viewing a DIFFERENT session) → push to ALL devices", async () => {
  const vt = new ViewingTracker()
  vt.update("phone", { session: "OTHER", visible: true }) // viewing a different session
  vt.update("tablet", { session: null, visible: false }) // backgrounded
  const sent: string[] = []
  await fire(vt, sent, ["phone", "tablet", "watch"])
  expect(sent.sort()).toEqual(["phone", "tablet", "watch"]) // none present for S → all notified
})

test("muted session → no pushes at all", async () => {
  const vt = new ViewingTracker()
  const sent: string[] = []
  await fire(vt, sent, ["a", "b"], () => true)
  expect(sent).toEqual([])
})

test("non-web chat_id is ignored (telegram path untouched)", async () => {
  const sent: string[] = []
  await firePushForReply({
    sender: fakeSender(sent),
    action: { op: "reply", chat_id: "telegram:123", text: "x" } as any,
    sessionName: "s",
    sessionId: "S",
    isMuted: () => false,
    devices: () => ["a"],
    anyPresent: () => false,
  })
  expect(sent).toEqual([])
})
