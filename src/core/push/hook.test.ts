import { expect, test } from "bun:test"
import { firePushForReply, firePushForRequest, firePushToAll, extractPreview } from "./hook"
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
  const { args, nativeCalls } = baseArgs({ presentOn: () => ["macos-3"] })
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
  const { args, nativeCalls } = baseArgs({ presentOn: () => ["macos-3"], log })
  await firePushForReply(args as any)
  expect(nativeCalls).toEqual([])
  expect(lines).toEqual([
    { level: "info", msg: "push_decision", extra: { session: "s", sessionId: "sid", outcome: "present", presentOn: ["macos-3"] } },
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

// #123: an attachment's preview is worded in each web device's language; native devices get English (the apps
// re-word it from `kind`), and the user's own text is never touched.
test("extractPreview words an attachment in the asked language", () => {
  const photo = { op: "reply", chat_id: "web", text: "", attachments: [{ kind: "photo", file_id: "f1" }] } as any
  expect(extractPreview(photo)).toBe("📷 Photo")
  expect(extractPreview(photo, "tr")).toBe("📷 Fotoğraf")
  expect(extractPreview({ op: "reply", chat_id: "web", text: "" } as any, "tr")).toBe("Yeni mesaj")
  expect(extractPreview({ op: "reply", chat_id: "web", text: "", attachments: [{ kind: "document", file_id: "f" }] } as any, "tr")).toBe("📎 Dosya")
  expect(extractPreview({ op: "reply", chat_id: "web", text: "", attachments: [{ kind: "document", file_id: "f", name: "a.pdf" }] } as any, "tr")).toBe("📎 a.pdf")
  expect(extractPreview({ op: "reply", chat_id: "web", text: "merhaba" } as any, "tr")).toBe("merhaba")
})

test("a tr web device gets a Turkish preview, an en one and native devices get English", async () => {
  const web: Record<string, string> = {}
  const native: string[] = []
  const { args } = baseArgs({
    action: { op: "reply", chat_id: "web", text: "", attachments: [{ kind: "photo", file_id: "f1" }] },
    devices: () => ["tr-browser", "en-browser", "old-browser"],
    langOf: (d: string) => (d === "tr-browser" ? "tr-TR" : d === "en-browser" ? "en" : undefined),
    sender: { sendToDevice: async (d: string, p: any) => { web[d] = p.text; return { ok: true as const } }, sendToChat: async () => ({ ok: true as const }) },
    nativeSender: { sendToDevice: async (_d: string, p: any) => { native.push(p.text); return { ok: true as const } } },
  })
  await firePushForReply(args as any)
  expect(web).toEqual({ "tr-browser": "📷 Fotoğraf", "en-browser": "📷 Photo", "old-browser": "📷 Photo" })
  expect(native).toEqual(["📷 Photo"])
})

test("firePushToAll words a notice per web device when given textFor; native gets English", async () => {
  const web: Record<string, string> = {}
  const native: string[] = []
  await firePushToAll({
    sender: { sendToDevice: async (d: string, p: any) => { web[d] = p.text; return { ok: true as const } }, sendToChat: async () => ({ ok: true as const }) } as any,
    nativeSender: { sendToDevice: async (_d: string, p: any) => { native.push(p.text); return { ok: true as const } } } as any,
    devices: () => ["a", "b"],
    nativeDevices: () => ["phone"],
    langOf: (d) => (d === "a" ? "tr" : undefined),
    textFor: (lang) => (lang === "tr" ? "türkçe" : "english"),
    payload: { session: "s", text: "fallback", ts: "t" },
    trigger: "finish",
  })
  expect(web).toEqual({ a: "türkçe", b: "english" })
  expect(native).toEqual(["english"])
})

test("firePushToAll without textFor sends the payload's text as it is", async () => {
  const web: string[] = []
  await firePushToAll({
    sender: { sendToDevice: async (_d: string, p: any) => { web.push(p.text); return { ok: true as const } }, sendToChat: async () => ({ ok: true as const }) } as any,
    devices: () => ["a"],
    langOf: () => "tr",
    payload: { session: "curator", text: "curator notice", ts: "t" },
    trigger: "curator",
  })
  expect(web).toEqual(["curator notice"])
})

// #143: a permission prompt nobody saw blocked #117 for 23 min. A prompt pushes like a reply, with what a client
// needs to answer from the notification.
function requestArgs(over: any = {}) {
  const sent: { device: string; payload: any }[] = []
  const args = {
    sender: { sendToDevice: async () => ({ ok: true as const }), sendToChat: async () => ({ ok: true as const }) },
    sessionName: "Applets (#117)", sessionId: "sid",
    request: {
      requestId: "r1", kind: "permission" as const, title: "Allow Bash?", body: "rm -f ~/work/probe/*\nadb shell …",
      options: [{ id: "allow_once", label: "Allow" }, { id: "reject_once", label: "Reject" }],
    },
    isInternal: () => false,
    devices: () => [] as string[],
    presentOn: () => [] as string[],
    nativeSender: { sendToDevice: async (device: string, payload: any) => { sent.push({ device, payload }); return { ok: true as const } } },
    nativeDevices: () => ["phone"],
    hostId: "h1",
    ...over,
  }
  return { args, sent }
}

test("a permission prompt pushes with its question, the first line of what it asks, and its answers", async () => {
  const { args, sent } = requestArgs()
  await firePushForRequest(args as any)
  expect(sent).toHaveLength(1)
  expect(sent[0]!.payload).toMatchObject({
    session: "Applets (#117)", sessionId: "sid", hostId: "h1",
    text: "Allow Bash? rm -f ~/work/probe/*",
    request: { requestId: "r1", kind: "permission", options: [{ id: "allow_once", label: "Allow" }, { id: "reject_once", label: "Reject" }] },
  })
})

test("a prompt pushes even when the session is muted (the agent is blocked), but never for an internal worker", async () => {
  const muted = requestArgs({ isMuted: () => true })
  await firePushForRequest(muted.args as any)
  expect(muted.sent).toHaveLength(1)
  const internal = requestArgs({ isInternal: () => true })
  await firePushForRequest(internal.args as any)
  expect(internal.sent).toHaveLength(0)
})

test("a prompt is not pushed to a device already showing the session", async () => {
  const { args, sent } = requestArgs({ presentOn: () => ["phone"] })
  await firePushForRequest(args as any)
  expect(sent).toHaveLength(0)
})
