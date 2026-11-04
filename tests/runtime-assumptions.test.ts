// Facts about the runtime that supermux's own code relies on. Each one is a behaviour we chose to build on, or
// around, after checking it (see the platform-layer spec, §3.2). If a Bun upgrade changes one, this file goes red
// before users notice. The Windows lane runs it too, so a fact that differs on Windows shows up there.
import { expect, test } from "bun:test"
import { homedir, tmpdir } from "node:os"
import { join } from "node:path"

// Why src/shared/home.ts reads the environment itself instead of calling os.homedir(): Bun's os.homedir() keeps the
// home of the environment the process STARTED with (checked on 1.3.14 and 1.4.4; Node follows the change). So a test
// preload, or anything that moves HOME / USERPROFILE at runtime, is invisible to os.homedir().
test("Bun's os.homedir() ignores HOME and USERPROFILE set after start", () => {
  const before = homedir()
  const saved = { HOME: process.env.HOME, USERPROFILE: process.env.USERPROFILE }
  process.env.HOME = process.env.USERPROFILE = join(tmpdir(), "mux-not-a-home")
  try {
    expect(homedir()).toBe(before)
  } finally {
    // Assigning undefined would store the string "undefined": delete instead.
    for (const [key, value] of Object.entries(saved)) {
      if (value === undefined) delete process.env[key]
      else process.env[key] = value
    }
  }
})
