import { describe, expect, test } from "bun:test"
import { buildNestedChildCommand } from "./conpty-smoke"

describe("ConPTY smoke command", () => {
  test("keeps Start-Process parameters in one statement and delimits only the output statement", () => {
    const command = buildNestedChildCommand()

    expect(command).toContain(
      "$child = Start-Process -FilePath powershell.exe -ArgumentList @('-NoLogo','-NoProfile','-Command','Start-Sleep -Seconds 600') -PassThru",
    )
    expect(command).toContain("; Write-Output ('SUPERMUX_CHILD_PID=' + $child.Id)\r")
    expect(command).not.toContain("; -ArgumentList")
    expect(command).not.toContain("; -PassThru")
    expect(command.match(/;/gu)).toHaveLength(1)
  })

  test("can import command construction without executing the Windows-only smoke", () => {
    expect(typeof buildNestedChildCommand).toBe("function")
  })
})
