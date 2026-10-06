import { expect, test } from "bun:test"
import { spawn } from "child_process"
import { paneSessionId } from "./pane-owner"

test("reads MUX_SESSION_ID from the process or its child, null when absent", async () => {
  // A shell (like a tmux pane) whose child carries the id, as `bash -lc 'exec env … claude'` does.
  const pane = spawn("bash", ["-c", "env MUX_SESSION_ID=sess-123 sleep 5 & wait"], { stdio: "ignore", env: { PATH: process.env.PATH! } })
  try {
    await Bun.sleep(300)
    expect(paneSessionId(pane.pid!)).toBe("sess-123")
    expect(paneSessionId(process.pid)).toBe(process.env.MUX_SESSION_ID ?? null)
    expect(paneSessionId(999_999_999)).toBeNull()
  } finally {
    pane.kill("SIGKILL")
  }
})
