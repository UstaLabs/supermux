import { test, expect } from "bun:test"
import { CommandRegistry, ClaudeCommandProvider, CodexCommandProvider, CursorCommandProvider, OpenCodeCommandProvider, controlCommands } from "./index"

test("barrel exports the full surface", () => {
  expect(typeof CommandRegistry).toBe("function")
  expect(typeof ClaudeCommandProvider).toBe("function")
  expect(typeof CodexCommandProvider).toBe("function")
  expect(typeof CursorCommandProvider).toBe("function")
  expect(typeof OpenCodeCommandProvider).toBe("function")
  expect(controlCommands({ muted: false })).toHaveLength(6)
})
