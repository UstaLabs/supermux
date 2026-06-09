import { test, expect } from "bun:test"
import { mkdtempSync, rmSync, readFileSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import {
  SOUL_SETUP_INVOCATION,
  readSoulSetupState,
  writeSoulSetupState,
  shouldAutoSendSoulSetup,
  appendSoulSetupInvocation,
} from "./soul-setup"
import type { Message } from "./messages"
import type { Session } from "./types"

function pa(overrides: Partial<Session> = {}): Session {
  return {
    id: "pa-1",
    name: "assistant",
    status: "active",
    agent: "claude",
    workdir: "/tmp",
    mute: false,
    can_orchestrate: true,
    role: "personal_assistant",
    is_default: true,
    tmux_target: "mux:assistant",
    pid: 1,
    connected: true,
    created_at: "2026-06-05T00:00:00.000Z",
    ...overrides,
  } as Session
}

function inbound(text: string): Message {
  return {
    id: "in:web:1",
    ts: "2026-06-05T00:00:01.000Z",
    direction: "inbound",
    channel: "web",
    chat_id: "web",
    message_id: "1",
    text,
  }
}

test("readSoulSetupState defaults to pending when file is missing or invalid", () => {
  const root = mkdtempSync(join(tmpdir(), "soul-state-"))
  try {
    const file = join(root, "missing.json")
    expect(readSoulSetupState(file).status).toBe("pending")
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})

test("writeSoulSetupState persists skipped/completed state", () => {
  const root = mkdtempSync(join(tmpdir(), "soul-state-write-"))
  try {
    const file = join(root, "soul-setup.json")
    writeSoulSetupState("skipped", file)
    expect(readSoulSetupState(file).status).toBe("skipped")
    expect(JSON.parse(readFileSync(file, "utf8")).updatedAt).toBeTruthy()
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})

test("shouldAutoSendSoulSetup only allows fresh default PA with pending state", () => {
  expect(shouldAutoSendSoulSetup({ session: pa(), messages: [], state: { status: "pending" }, alreadyQueued: false })).toBe(true)
  expect(shouldAutoSendSoulSetup({ session: pa({ role: "worker", is_default: false }), messages: [], state: { status: "pending" }, alreadyQueued: false })).toBe(false)
  expect(shouldAutoSendSoulSetup({ session: pa({ is_default: false }), messages: [], state: { status: "pending" }, alreadyQueued: false })).toBe(false)
  expect(shouldAutoSendSoulSetup({ session: pa(), messages: [], state: { status: "skipped" }, alreadyQueued: false })).toBe(false)
  expect(shouldAutoSendSoulSetup({ session: pa(), messages: [], state: { status: "completed" }, alreadyQueued: false })).toBe(false)
  expect(shouldAutoSendSoulSetup({ session: pa(), messages: [inbound("hello")], state: { status: "pending" }, alreadyQueued: false })).toBe(false)
  expect(shouldAutoSendSoulSetup({ session: pa(), messages: [], state: { status: "pending" }, alreadyQueued: true })).toBe(false)
})

test("shouldAutoSendSoulSetup ignores an earlier synthetic soul setup message", () => {
  expect(shouldAutoSendSoulSetup({
    session: pa(),
    messages: [{
      ...inbound(SOUL_SETUP_INVOCATION),
      chat_id: "web:setup",
    }],
    state: { status: "pending" },
    alreadyQueued: false,
  })).toBe(true)
})

test("appendSoulSetupInvocation appends a visible inbound message and delivers it", async () => {
  const appended: Array<{ sessionId: string; entry: Message }> = []
  const delivered: Array<{ sessionId: string; text: string; meta: Record<string, string> }> = []
  await appendSoulSetupInvocation({
    session: pa(),
    messageLog: { append: (sessionId: string, entry: Message) => appended.push({ sessionId, entry }) },
    deliver: async (sessionId, text, meta) => {
      delivered.push({ sessionId, text, meta })
    },
    now: () => new Date("2026-06-05T00:00:02.000Z"),
  })

  expect(appended).toHaveLength(1)
  expect(appended[0]!.sessionId).toBe("pa-1")
  expect(appended[0]!.entry.direction).toBe("inbound")
  expect(appended[0]!.entry.channel).toBe("web")
  expect(appended[0]!.entry.chat_id).toBe("web:setup")
  expect(appended[0]!.entry.text).toBe(SOUL_SETUP_INVOCATION)
  expect(delivered).toEqual([{
    sessionId: "pa-1",
    text: SOUL_SETUP_INVOCATION,
    meta: {
      chat_id: "web:setup",
      message_id: appended[0]!.entry.message_id!,
      user: "supermux",
      user_id: "system",
      ts: "2026-06-05T00:00:02.000Z",
      system_generated: "soul_setup",
    },
  }])
})
