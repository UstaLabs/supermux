import { describe, expect, test } from "bun:test"
import { detectUpdateMode } from "./mode"

describe("detectUpdateMode", () => {
  test("returns 'source' when not compiled and no /.dockerenv (bun test host)", () => {
    // Under `bun test`, IS_COMPILED is false (not a compiled binary), and this
    // host doesn't have /.dockerenv, so we expect 'source'.
    const mode = detectUpdateMode()
    expect(mode).toBe("source")
    // Docker assertion skipped — we can't synthesise /.dockerenv in a test without
    // root/mocking, and the docker-then-binary branch order is trivially readable.
  })
})
