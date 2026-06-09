import { test, expect, beforeEach, afterEach } from "bun:test"
import { handleSlash, CommandCtx } from "../src/core/commands"
import { Registry } from "../src/core/session-manager/registry"
import { MessageStore } from "../src/core/session-manager/messages"
import { openDb, runMigrations } from "../src/core/storage/db"
import type { Database } from "bun:sqlite"
import { mkdtempSync, rmSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"

let r: Registry
let messageLog: MessageStore
let ctx: CommandCtx
let spawnedPA: any[] = []
let tmpDir: string
let db: Database

beforeEach(() => {
  tmpDir = mkdtempSync(join(tmpdir(), "cmux-pa-cmd-"))
  db = openDb(join(tmpDir, "test.sqlite3"))
  runMigrations(db, join(import.meta.dir, "../src/core/storage/migrations"))
  r = new Registry(db)
  messageLog = new MessageStore(db)
  r.register({ name: "ana", workdir: "/h", tmux_target: "mux:ana", pid: 1, role: "personal_assistant", is_default: true })
  r.register({ name: "zoom", workdir: "/z", tmux_target: "mux:zoom", pid: 2 })
  spawnedPA = []
  ctx = {
    registry: r,
    messageLog,
    chat_id: "chat-1",
    spawnSession: async () => ({ name: "n", session_id: "s" }),
    killSession: async () => {},
    refreshMenu: async () => {},
    spawnPA: async (args) => {
      spawnedPA.push(args)
      return { name: args.name, id: "pa-id-1", workdir: "/home/.mux/workspace/" + args.name, agent: args.agent ?? "claude", model: args.model }
    },
  }
})

afterEach(() => {
  db.close()
  rmSync(tmpDir, { recursive: true, force: true })
})

test("/spawnpa <name> creates a PA with default agent claude", async () => {
  const result = await handleSlash({ command: "spawnpa", rest: "my-pa" }, ctx)
  expect(spawnedPA).toHaveLength(1)
  expect(spawnedPA[0].name).toBe("my-pa")
  expect(spawnedPA[0].agent).toBeUndefined()
  expect(result.text).toContain("my-pa")
  expect(result.text).toContain("claude")
})

test("/spawnpa <name> --agent codex passes agent kind", async () => {
  const result = await handleSlash({ command: "spawnpa", rest: "my-pa --agent codex" }, ctx)
  expect(spawnedPA).toHaveLength(1)
  expect(spawnedPA[0].agent).toBe("codex")
  expect(result.text).toContain("codex")
})

test("/spawnpa <name> --model gpt-4 passes model", async () => {
  const result = await handleSlash({ command: "spawnpa", rest: "my-pa --model gpt-4" }, ctx)
  expect(spawnedPA).toHaveLength(1)
  expect(spawnedPA[0].model).toBe("gpt-4")
  expect(result.text).toContain("gpt-4")
})

test("/spawnpa <name> --focus 'some focus' passes focus text", async () => {
  const result = await handleSlash({ command: "spawnpa", rest: "my-pa --focus 'some focus'" }, ctx)
  expect(spawnedPA).toHaveLength(1)
  expect(spawnedPA[0].focus).toBe("'some focus'")
  expect(result.text).toContain("my-pa")
})

test("/spawnpa with missing name returns usage error", async () => {
  const result = await handleSlash({ command: "spawnpa", rest: "" }, ctx)
  expect(result.text).toMatch(/usage:/i)
  expect(spawnedPA).toHaveLength(0)
})

test("/spawnpa with duplicate name returns error", async () => {
  const result = await handleSlash({ command: "spawnpa", rest: "ana" }, ctx)
  expect(result.text).toMatch(/already in use|already exists/i)
  expect(spawnedPA).toHaveLength(0)
})

test("/spawnpa rejects invalid agent kind", async () => {
  const result = await handleSlash({ command: "spawnpa", rest: "my-pa --agent blorp" }, ctx)
  expect(result.text).toMatch(/unknown agent/i)
  expect(spawnedPA).toHaveLength(0)
})

test("/pas lists all personal assistants with default marked", async () => {
  r.register({ name: "bob", workdir: "/b", tmux_target: "mux:bob", pid: 3, role: "personal_assistant", is_default: false })
  const result = await handleSlash({ command: "pas", rest: "" }, ctx)
  expect(result.text).toContain("ana")
  expect(result.text).toContain("bob")
  // Default PA should be marked with a star
  expect(result.text).toContain("*")
})

test("/pas with no personal assistants returns empty state", async () => {
  r.unregister(r.resolveName("ana")!.id)
  const result = await handleSlash({ command: "pas", rest: "" }, ctx)
  expect(result.text).toMatch(/no personal assistants|none/i)
})

test("/pas shows agent, model, workdir, and status", async () => {
  const anaId = r.resolveName("ana")!.id
  r.get(anaId)!.agent = "claude"
  r.get(anaId)!.model = "opus-4"
  r.get(anaId)!.connected = true
  const result = await handleSlash({ command: "pas", rest: "" }, ctx)
  expect(result.text).toContain("claude")
  expect(result.text).toContain("opus-4")
  expect(result.text).toContain("/h")
})
