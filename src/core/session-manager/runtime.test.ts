import { describe, expect, test } from "bun:test"
import { AgentKind } from "../../shared/agents"
import { RuntimeRegistry } from "./runtime"

describe("RuntimeRegistry", () => {
  test("stores and retrieves typed runtime entries by session id", () => {
    const runtimes = new RuntimeRegistry()
    const adapter = {
      kind: AgentKind.Cursor,
      sessionName: "c",
      workdir: "/tmp",
      start: async () => {},
      resume: async () => {},
      stop: async () => {},
      send: async () => {},
      interrupt: async () => {},
      on: () => adapter,
      emit: () => false,
    } as unknown as import("../agents/cursor/adapter").CursorAdapter
    runtimes.set("sid", { kind: AgentKind.Cursor, adapter })
    expect(runtimes.get("sid")?.kind).toBe(AgentKind.Cursor)
  })

  test("deletes runtime entries", () => {
    const runtimes = new RuntimeRegistry()
    const adapter = {
      kind: AgentKind.Cursor,
      sessionName: "c",
      workdir: "/tmp",
      start: async () => {},
      resume: async () => {},
      stop: async () => {},
      send: async () => {},
      interrupt: async () => {},
      on: () => adapter,
      emit: () => false,
    } as unknown as import("../agents/cursor/adapter").CursorAdapter
    runtimes.set("sid", { kind: AgentKind.Cursor, adapter })
    runtimes.delete("sid")
    expect(runtimes.get("sid")).toBeUndefined()
  })
})
