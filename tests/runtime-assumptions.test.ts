// Facts about the runtime that supermux's own code relies on. Each one is a behaviour we chose to build on, or
// around, after checking it (see the platform-layer spec, §3.2). If a Bun upgrade changes one, this file goes red
// before users notice. The Windows lane runs it too, so a fact that differs on Windows shows up there.
import { expect, test } from "bun:test"
import { homedir, tmpdir } from "node:os"
import { join } from "node:path"

// Why src/shared/home.ts reads the environment itself instead of calling os.homedir(): Bun's os.homedir() is not a
// dependable view of HOME / USERPROFILE set at runtime (a test preload, an override). On Linux and macOS it keeps
// the home the process STARTED with; on Windows it follows a USERPROFILE set later (checked on 1.3.14 and 1.4.4;
// Node follows the change everywhere). Code that must honour an override reads process.env.
test("Bun's os.homedir() follows a runtime USERPROFILE on Windows, and ignores a runtime HOME elsewhere", () => {
  const before = homedir()
  const elsewhere = join(tmpdir(), "mux-not-a-home")
  const saved = { HOME: process.env.HOME, USERPROFILE: process.env.USERPROFILE }
  process.env.HOME = process.env.USERPROFILE = elsewhere
  try {
    expect(homedir()).toBe(process.platform === "win32" ? elsewhere : before)
  } finally {
    // Assigning undefined would store the string "undefined": delete instead.
    for (const [key, value] of Object.entries(saved)) {
      if (value === undefined) delete process.env[key]
      else process.env[key] = value
    }
  }
})
