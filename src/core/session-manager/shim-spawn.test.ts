import { describe, expect, test } from "bun:test"
import { shimSpawnSpec } from "./shim-spawn"

describe("shimSpawnSpec", () => {
  test("source mode: bun + absolute shim entry path", () => {
    const s = shimSpawnSpec()
    expect(s.shimCommand).toBe("bun")
    expect(s.shimArgs[0]).toBe("run")
    expect(s.shimArgs[1]).toEndWith("/src/shim/index.ts")
    // absolute (rename-proof), like the SHIM_ENTRY it replaces
    expect(s.shimArgs[1]!.startsWith("/")).toBe(true)
  })
})
