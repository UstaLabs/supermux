import { expect, test } from "bun:test"
import { firePushForReply, firePushToAll, extractPreview } from "./hook"
import type { Logger } from "../../shared/log"

function recorder() {
  const lines: { level: string; msg: string; extra?: Record<string, unknown> }[] = []
  const at = (level: string) => (msg: string, extra?: Record<string, unknown>) => { lines.push({ level, msg, extra }) }
  const log: Logger = { debug: at("debug"), info: at("info"), warn: at("warn"), error: at("error") }
  return { log, lines }
}

function baseArgs(over: any = {}) {
  const nativeCalls: string[] = []
  const args = {
    sender: { sendToDevice: async () => ({ ok: true as const }), sendToChat: async () => ({ ok: true as const }) },
    action: { op: "reply", chat_id: "web", text: "hi" },
    sessionName: "s", sessionId: "sid",
    isMuted: () => false,
    devices: () => [] as string[],
    presentOn: () => [],
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
  const { args, nativeCalls } = baseArgs({ presentOn: () => [{ device: "macos-3", onList: true }] })
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

// Card #86: the broker logged nothing about pushes, so "sent", "skipped" and
// "never registered" all looked the same. One line per reply says which it was.
test("logs the decision to send, naming every target", async () => {
  const { log, lines } = recorder()
  const { args } = baseArgs({ devices: () => ["browser"], log })
  await firePushForReply(args as any)
  expect(lines).toEqual([
    { level: "info", msg: "push_decision", extra: { session: "s", sessionId: "sid", outcome: "sent", web: ["browser"], native: ["phone"] } },
  ])
})

test("warns when there is nobody to push to", async () => {
  const { log, lines } = recorder()
  const { args } = baseArgs({ nativeDevices: () => [], log })
  await firePushForReply(args as any)
  expect(lines).toEqual([
    { level: "warn", msg: "push_decision", extra: { session: "s", sessionId: "sid", outcome: "no_targets" } },
  ])
})

test("logs which device made the push redundant", async () => {
  const { log, lines } = recorder()
  const { args, nativeCalls } = baseArgs({ presentOn: () => [{ device: "macos-3", onList: true }], log })
  await firePushForReply(args as any)
  expect(nativeCalls).toEqual([])
  expect(lines).toEqual([
    { level: "info", msg: "push_decision", extra: { session: "s", sessionId: "sid", outcome: "present", presentOn: [{ device: "macos-3", onList: true }] } },
  ])
})

test("logs a muted session's skipped push", async () => {
  const { log, lines } = recorder()
  const { args } = baseArgs({ isMuted: () => true, log })
  await firePushForReply(args as any)
  expect(lines.map((l) => l.extra?.outcome)).toEqual(["muted"])
})

test("logs an internal worker's skipped push", async () => {
  const { log, lines } = recorder()
  const { args } = baseArgs({ isInternal: () => true, log })
  await firePushForReply(args as any)
  expect(lines.map((l) => l.extra?.outcome)).toEqual(["internal"])
})

test("logs a reply that went to another channel", async () => {
  const { log, lines } = recorder()
  const { args, nativeCalls } = baseArgs({ action: { op: "reply", chat_id: "telegram:42", text: "hi" }, log })
  await firePushForReply(args as any)
  expect(nativeCalls).toEqual([])
  expect(lines).toEqual([
    { level: "info", msg: "push_decision", extra: { session: "s", sessionId: "sid", outcome: "other_channel", channel: "telegram" } },
  ])
})

// The finish and curator notices skip mute and presence by design; they log the
// same decision line, tagged with what triggered them.
test("a broker notice goes to every device and logs the decision with its trigger", async () => {
  const { log, lines } = recorder()
  const webCalls: string[] = []
  const nativeCalls: string[] = []
  await firePushToAll({
    sender: { sendToDevice: async (d: string) => { webCalls.push(d); return { ok: true as const } }, sendToChat: async () => ({ ok: true as const }) },
    nativeSender: { sendToDevice: async (d: string) => { nativeCalls.push(d); return { ok: true as const } } },
    devices: () => ["browser"],
    nativeDevices: () => ["phone"],
    payload: { session: "curator", text: "done", ts: "t" },
    trigger: "curator",
    log,
  })
  expect(webCalls).toEqual(["browser"])
  expect(nativeCalls).toEqual(["phone"])
  expect(lines).toEqual([
    { level: "info", msg: "push_decision", extra: { session: "curator", trigger: "curator", outcome: "sent", web: ["browser"], native: ["phone"] } },
  ])
})

test("a broker notice with nobody to push to is a warning", async () => {
  const { log, lines } = recorder()
  await firePushToAll({
    sender: { sendToDevice: async () => ({ ok: true as const }), sendToChat: async () => ({ ok: true as const }) },
    devices: () => [],
    payload: { session: "s", sessionId: "sid", text: "finished", ts: "t" },
    trigger: "finish",
    log,
  })
  expect(lines).toEqual([
    { level: "warn", msg: "push_decision", extra: { session: "s", sessionId: "sid", trigger: "finish", outcome: "no_targets" } },
  ])
})
