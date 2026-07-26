import { describe, expect, test } from "bun:test"
import {
  buildInputMarkerCommand,
  buildNestedChildCommand,
  buildOutputMarkerCommand,
  buildSmokeShellArgv,
  withTimeout,
} from "./conpty-smoke"

describe("ConPTY smoke command", () => {
  test("uses cmd.exe as the authoritative Windows ConPTY shell", () => {
    expect(buildSmokeShellArgv()).toEqual(["cmd.exe", "/d", "/k"])
  })

  test("composes markers at execution time so ConPTY input echo cannot satisfy the matchers", () => {
    expect(buildOutputMarkerCommand()).toContain("for %i in (OUTPUT_OK)")
    expect(buildOutputMarkerCommand()).toContain("@echo SUPERMUX_CONPTY_%i")
    expect(buildOutputMarkerCommand()).not.toContain("SUPERMUX_CONPTY_OUTPUT_OK")
    expect(buildInputMarkerCommand()).toContain("for %i in (OK)")
    expect(buildInputMarkerCommand()).not.toContain("SUPERMUX_CONPTY_OK")
  })

  test("starts a nested long-lived child that shares the console Job Object", () => {
    const command = buildNestedChildCommand()
    expect(command).toContain("start /B cmd")
    expect(command).toContain("ping -n 600")
  })

  test("bounds a stalled cleanup promise", async () => {
    await expect(withTimeout(new Promise<void>(() => {}), 5, "cleanup")).rejects.toThrow(/cleanup.*5ms/i)
    await expect(withTimeout(Promise.resolve("ok"), 5, "cleanup")).resolves.toBe("ok")
  })
})
