import { describe, expect, test } from "bun:test"
import { isFileEndpoint, legacyPipeEndpoint, localEndpoint, localEndpoints, MAX_WINDOWS_SOCKET_PATH, safePipeComponent } from "./local-endpoint"

describe("localEndpoint", () => {
  test("uses a filesystem socket on POSIX", () => {
    expect(localEndpoint("abc", { platform: "linux", socketsDir: "/state/sockets" })).toBe("/state/sockets/abc.sock")
  })

  test("#156: Windows uses an AF_UNIX file too, so the broker can ask who connected", () => {
    expect(localEndpoint("abc", { platform: "win32", socketsDir: "C:\\Users\\me\\.mux\\state\\sockets" })).toBe("C:\\Users\\me\\.mux\\state\\sockets\\abc.sock")
  })

  test("a Windows path too long for sun_path keeps the named pipe", () => {
    const dir = "C:\\" + "x".repeat(MAX_WINDOWS_SOCKET_PATH)
    expect(localEndpoint("A session/1", { platform: "win32", socketsDir: dir })).toBe("\\\\.\\pipe\\supermux-session-A_session_1")
  })

  test("Windows clients try the file, then the pre-#156 pipe (a broker not yet restarted serves only that)", () => {
    expect(localEndpoints("abc", { platform: "win32", socketsDir: "C:\\s" })).toEqual(["C:\\s\\abc.sock", legacyPipeEndpoint("abc")])
    expect(localEndpoints("abc", { platform: "linux", socketsDir: "/s" })).toEqual(["/s/abc.sock"])
  })

  test("pipes are not files", () => {
    expect(isFileEndpoint(legacyPipeEndpoint("abc"))).toBe(false)
    expect(isFileEndpoint("C:\\s\\abc.sock")).toBe(true)
  })

  test("sanitizes pipe components", () => {
    expect(safePipeComponent("a:b/c\\d")).toBe("a_b_c_d")
  })
})
