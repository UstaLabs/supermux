import { describe, expect, test } from "bun:test"
import {
  buildInputMarkerCommand,
  buildNestedChildCommand,
  buildOutputMarkerCommand,
  buildSmokeShellArgv,
  withTimeout,
} from "./conpty-smoke"

describe("ConPTY smoke command", () => {
  test("uses PowerShell as the product Windows ConPTY shell", () => {
    expect(buildSmokeShellArgv()).toEqual(["powershell.exe", "-NoLogo", "-NoProfile"])
  })

  test("composes markers so ConPTY input echo cannot satisfy the matchers alone", () => {
    expect(buildOutputMarkerCommand()).toContain("SUPERMUX_CONPTY_")
    expect(buildOutputMarkerCommand()).toContain("OUTPUT_OK")
    expect(buildOutputMarkerCommand()).not.toContain("SUPERMUX_CONPTY_OUTPUT_OK")
    expect(buildInputMarkerCommand()).toContain("SUPERMUX_CONPTY_")
    expect(buildInputMarkerCommand()).not.toContain("SUPERMUX_CONPTY_OK")
  })

  test("starts a nested long-lived PowerShell child for Job Object kill coverage", () => {
    const command = buildNestedChildCommand()
    expect(command).toContain("Start-Process -FilePath powershell.exe")
    expect(command).toContain("Start-Sleep -Seconds 600")
    expect(command).toContain("SUPERMUX_CHILD_PID=")
  })

  test("bounds a stalled cleanup promise", async () => {
    await expect(withTimeout(new Promise<void>(() => {}), 5, "cleanup")).rejects.toThrow(/cleanup.*5ms/i)
    await expect(withTimeout(Promise.resolve("ok"), 5, "cleanup")).resolves.toBe("ok")
  })
})
