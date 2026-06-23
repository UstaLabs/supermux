import { expect, test } from "bun:test"
import { firePushForReply } from "./hook"

function baseArgs(over: any = {}) {
  const nativeCalls: string[] = []
  const args = {
    sender: { sendToDevice: async () => ({ ok: true as const }), sendToChat: async () => ({ ok: true as const }) },
    action: { op: "reply", chat_id: "web", text: "hi" },
    sessionName: "s", sessionId: "sid",
    isMuted: () => false,
    devices: () => [] as string[],
    anyPresent: () => false,
    nativeSender: { sendToDevice: async (d: string) => { nativeCalls.push(d); return { ok: true as const } } },
    nativeDevices: () => ["phone"],
    ...over,
  }
  return { args, nativeCalls }
}

test("fans the reply to native devices when not muted and not present", async () => {
  const { args, nativeCalls } = baseArgs()
  await firePushForReply(args as any)
  expect(nativeCalls).toEqual(["phone"])
})

test("does NOT fire native when the session is muted", async () => {
  const { args, nativeCalls } = baseArgs({ isMuted: () => true })
  await firePushForReply(args as any)
  expect(nativeCalls).toEqual([])
})

test("does NOT fire native when the user is present on any device", async () => {
  const { args, nativeCalls } = baseArgs({ anyPresent: () => true })
  await firePushForReply(args as any)
  expect(nativeCalls).toEqual([])
})
