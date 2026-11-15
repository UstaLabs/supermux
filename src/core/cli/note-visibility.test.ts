// #156, asked by Ahmet before the note went live: the one-time CLI note is for the AGENT only. The user's bubble and
// the stored message log (what every client shows, also after a reload) keep exactly the user's words.
import { afterEach, expect, test } from "bun:test"
import { join } from "path"
import { openDb, runMigrations } from "../storage/db"
import { Registry } from "../session-manager/registry"
import { SessionManager } from "../session-manager/manager"
import { MessageStore } from "../session-manager/messages"
import { handleWebInbound } from "../../channels/web/inbound-handler"
import { fakePorts } from "../../../tests/helpers/session-manager-ports"
import type { CoreAdapter } from "../agents/core-bridge/core-adapter"
import type { CodexSpawnHandle } from "../agents/codex/spawn"
import { bindCliAdoption, CliAdoption } from "./adoption"

const unbinds: Array<() => void> = []
afterEach(() => { for (const u of unbinds.splice(0)) u() })

test("the agent gets the note in front of the user's message; the user's bubble and the stored log never contain it", async () => {
  unbinds.push(bindCliAdoption(new CliAdoption()))       // on, and this session has not been told yet
  const db = openDb(":memory:")
  runMigrations(db, join(import.meta.dirname, "../storage/migrations"))
  const registry = new Registry(db)
  const m = new SessionManager(registry, fakePorts(db))
  const messageLog = new MessageStore(db)
  const s = registry.register({ name: "old", workdir: "/tmp", pid: 0, agent: "codex", connected: false })
  const toAgent: string[] = []
  m.registerRuntime(s.id, { kind: "codex", adapter: { send: async (text: string) => { toAgent.push(text) } } as unknown as CoreAdapter, handle: {} as CodexSpawnHandle })
  const send = (id: string, text: string) => handleWebInbound(
    { chat_id: "web:phone", message_id: id, ts: new Date().toISOString(), text, user: "ahmet", user_id: "u1", target_session_id: s.id } as any,
    { messageLog, deliver: async (sid, t, meta) => { await m.deliver(sid, t, meta) }, hasSession: () => true, replyNoSuchSession: async () => {} },
  )
  await send("m1", "hello")
  await send("m2", "and again")

  // the agent: the note once, in front of the first message only
  expect(toAgent[0]).toStartWith("<system-reminder>\nThis is a one-time note from supermux, not from the user. It needs no reply or acknowledgement")
  expect(toAgent[0]).toContain("</system-reminder>\n\nhello")
  expect(toAgent[0]!.endsWith("\n\nhello")).toBe(true)
  expect(toAgent[1]).toBe("and again")
  // the user: what is stored (and broadcast / reloaded from it) is exactly what they typed
  const stored = messageLog.get(s.id, 50)
  expect(stored.map((x) => x.text)).toEqual(["hello", "and again"])
  expect(JSON.stringify(stored)).not.toContain("system-reminder")
})
