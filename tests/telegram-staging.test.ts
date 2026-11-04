// Telegram's raw downloads used a fixed "/tmp/cmux-tg-dl": on Windows `<drive>:\tmp\cmux-tg-dl`, outside the user's
// profile; on a shared POSIX host a name another user can create first. Now it is this process's own folder (mkdtemp),
// under the OS temp dir the runtime reports.
import { expect, test } from "bun:test"
import { mkdtempSync, statSync } from "node:fs"
import { tmpdir } from "node:os"
import { join } from "node:path"
import { telegramStagingDir } from "../src/channels/telegram/inbound"

test("the download staging is this process's own private folder under the OS temp dir", () => {
  const custom = mkdtempSync(join(tmpdir(), "tg-tmp-"))
  const vars = process.platform === "win32" ? ["TEMP", "TMP"] : ["TMPDIR"]
  const saved = vars.map(v => process.env[v])
  for (const v of vars) process.env[v] = custom
  try {
    const dir = telegramStagingDir()
    expect(dir.startsWith(custom)).toBe(true)
    expect(dir).not.toBe(join(custom, "cmux-tg-dl"))
    expect(telegramStagingDir()).toBe(dir)
    if (process.platform !== "win32") expect(statSync(dir).mode & 0o777).toBe(0o700)
  } finally {
    vars.forEach((v, i) => { if (saved[i] === undefined) delete process.env[v]; else process.env[v] = saved[i] })
  }
})
