import { describe, expect, test } from "bun:test"
import { claudeCommand } from "./command"

const windowsFs = (...files: string[]) => ({
  fileExists: (path: string) => files.some((file) => file.toLowerCase() === path.toLowerCase()),
})

const NPM = "C:\\Users\\u\\AppData\\Roaming\\npm"
const NPM_EXE = `${NPM}\\node_modules\\@anthropic-ai\\claude-code\\bin\\claude.exe`

describe("claudeCommand", () => {
  test("stays the bare name outside Windows", () => {
    expect(claudeCommand({ PATH: "/usr/bin" }, "linux")).toBe("claude")
    expect(claudeCommand({ PATH: "/usr/bin" }, "darwin")).toBe("claude")
  })

  test("uses a native claude.exe on PATH", () => {
    const deps = windowsFs("C:\\Users\\u\\.local\\bin\\claude.exe", `${NPM}\\claude.cmd`)
    expect(claudeCommand({ Path: `${NPM};C:\\Users\\u\\.local\\bin` }, "win32", deps)).toBe("C:\\Users\\u\\.local\\bin\\claude.exe")
  })

  test("runs the claude.exe an npm install ships behind its claude.cmd", () => {
    const deps = windowsFs(`${NPM}\\claude.cmd`, `${NPM}\\claude.ps1`, NPM_EXE)
    expect(claudeCommand({ PATH: NPM }, "win32", deps)).toBe(NPM_EXE)
  })

  test("refuses an npm shim with no claude.exe behind it, naming the shim", () => {
    const deps = windowsFs(`${NPM}\\claude.cmd`)
    expect(() => claudeCommand({ PATH: NPM }, "win32", deps)).toThrow(`${NPM}\\claude.cmd`)
  })

  test("leaves a missing claude to the spawn, which reports it", () => {
    expect(claudeCommand({ PATH: "C:\\Empty" }, "win32", windowsFs())).toBe("claude")
  })
})
