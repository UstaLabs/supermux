import { test, expect, describe } from "bun:test"
import { spawnSync } from "child_process"
import { spawnSessionWindow, killSessionWindow, listSessionWindows } from "../src/core/session-manager/tmux"

const hasTmux = spawnSync("which", ["tmux"]).status === 0

describe.if(hasTmux)("tmux helpers (real tmux)", () => {
  test("spawn + list + kill", async () => {
    const session = "agentmux-test-" + Date.now()
    await spawnSessionWindow({ session, window: "w1", workdir: "/tmp", command: "sleep 60" })
    const wins = await listSessionWindows(session)
    expect(wins).toContain("w1")
    await killSessionWindow({ session, window: "w1" })
    // tmux session itself may auto-die when last window goes
  })
})
