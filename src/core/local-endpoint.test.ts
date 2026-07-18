import { describe, expect, test } from "bun:test"
import { localEndpoint, safePipeComponent } from "./local-endpoint"

describe("localEndpoint", () => {
  test("uses a filesystem socket on POSIX", () => {
    expect(localEndpoint("abc", { platform: "linux", socketsDir: "/state/sockets" }))
      .toBe("/state/sockets/abc.sock")
  })

  test("uses a deterministic named pipe on Windows", () => {
    expect(localEndpoint("A session/1", { platform: "win32", socketsDir: "ignored" }))
      .toBe("\\\\.\\pipe\\supermux-session-A_session_1")
  })

  test("sanitizes pipe components", () => {
    expect(safePipeComponent("a:b/c\\d")).toBe("a_b_c_d")
  })
})
