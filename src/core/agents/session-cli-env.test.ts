import { expect, test } from "bun:test"
import { sessionCliEnv } from "./session-cli-env"

test("every agent's process gets its session identity and the supermux launcher first on PATH", () => {
  const env = sessionCliEnv({ PATH: "/usr/bin:/bin" }, "codex", "sid", "Fix login", { platform: "linux", binDir: "/state/bin", socketsDir: "/state/sockets" })
  expect(env).toEqual({ MUX_SESSION_ID: "sid", MUX_DISPLAY_NAME: "Fix login", MUX_AGENT_KIND: "codex", MUX_SOCKETS_DIR: "/state/sockets", PATH: "/state/bin:/usr/bin:/bin" })
})

test("the bin dir is not added twice", () => {
  const env = sessionCliEnv({ PATH: "/state/bin:/usr/bin" }, "grok", "s", "n", { platform: "linux", binDir: "/state/bin", socketsDir: "/x" })
  expect(env.PATH).toBe("/state/bin:/usr/bin")
})

test("Windows keeps the env's own Path key and ; separator", () => {
  const env = sessionCliEnv({ Path: "C:\\Windows" }, "claude", "s", "n", { platform: "win32", binDir: "C:\\state\\bin", socketsDir: "C:\\s" })
  expect(env.Path).toBe("C:\\state\\bin;C:\\Windows")
  expect(env.PATH).toBeUndefined()
})
