import { expect, test } from "bun:test"
import { firePushForReply, extractPreview } from "./hook"

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

test("echoes the broker hostId into the native payload for multi-host routing", async () => {
  const seen: any[] = []
  const { args } = baseArgs({
    nativeSender: { sendToDevice: async (_d: string, p: any) => { seen.push(p); return { ok: true as const } } },
    hostId: "amsfen6cjgpd7pjqw6nimlahvy",
  })
  await firePushForReply(args as any)
  expect(seen[0]?.hostId).toBe("amsfen6cjgpd7pjqw6nimlahvy")
})

test("omits hostId when the broker has none (single-host/pre-multi-host)", async () => {
  const seen: any[] = []
  const { args } = baseArgs({
    nativeSender: { sendToDevice: async (_d: string, p: any) => { seen.push(p); return { ok: true as const } } },
  })
  await firePushForReply(args as any)
  expect("hostId" in (seen[0] ?? {})).toBe(false)
})

test("extractPreview labels a video attachment", () => {
  expect(extractPreview({ op: "reply", chat_id: "web", text: "", attachments: [{ kind: "video", file_id: "f1" }] } as any)).toBe("🎥 Video")
})
