import { test, expect } from "bun:test"
import { LoginManager } from "../src/core/agents/login/manager"

test("start(codex) creates a session; get returns its state; cancel ends it", () => {
  const changes: any[] = []
  const mgr = new LoginManager({
    paths: { home: "/home/u" },
    fileExists: () => false,
    spawnLogin: () => ({ onStdout: () => {}, onExit: () => {}, kill: () => {}, write: () => {} }),
    onChange: (kind, st) => changes.push({ kind, phase: st.phase }),
    setInterval: () => 1, clearInterval: () => {},
  })
  const st = mgr.start("codex")
  expect(st.phase).toBe("starting")
  expect(mgr.get("codex")?.phase).toBe("starting")
  mgr.cancel("codex")
  expect(mgr.get("codex")?.phase).toBe("cancelled")
})

test("start(claude) starts a real PTY login with needsCode=true", () => {
  const mgr = new LoginManager({
    paths: { home: "/home/u" }, fileExists: () => false,
    spawnLogin: () => ({ onStdout: () => {}, onExit: () => {}, kill: () => {}, write: () => {} }),
    onChange: () => {}, setInterval: () => 1, clearInterval: () => {},
  })
  const st = mgr.start("claude")
  expect(st.phase).toBe("starting")
  expect(st.kind).toBe("claude")
})

test("sendCode types the code, then Enter as \\r on its own: a Windows ConPTY never ends agy's line on \\n", async () => {
  const writes: string[] = []
  let outCb: (c: string) => void = () => {}
  const mgr = new LoginManager({
    paths: { home: "/home/u" }, fileExists: () => false,
    spawnLogin: () => ({
      onStdout: (cb: (c: string) => void) => { outCb = cb },
      onExit: () => {},
      kill: () => {},
      write: (data: string) => { writes.push(data) },
    }),
    onChange: () => {}, setInterval: () => 1, clearInterval: () => {},
  })
  mgr.start("antigravity")
  outCb("https://accounts.google.com/o/oauth2/auth?client_id=x&state=s1\r\nOr, paste the authorization code here and press Enter:")
  mgr.sendCode("antigravity", "  4/abc \n")
  expect(writes).toEqual(["4/abc"])
  await Bun.sleep(400)
  expect(writes).toEqual(["4/abc", "\r"])
})

test("Claude login succeeds when the macOS CLI reports a Keychain credential", () => {
  let exitCb: (code: number | null) => void = () => {}
  const mgr = new LoginManager({
    paths: { home: "/Users/u" },
    fileExists: () => false,
    hasCredential: (kind) => kind === "claude",
    spawnLogin: () => ({
      onStdout: () => {},
      onExit: (cb) => { exitCb = cb },
      kill: () => {},
      write: () => {},
    }),
    onChange: () => {},
    setInterval: () => 1,
    clearInterval: () => {},
  })

  mgr.start("claude")
  exitCb(0)

  expect(mgr.get("claude")?.phase).toBe("success")
})

test("reauth: already-authed agent does not poll-succeed; exits 0 → success", () => {
  let exitCb: (code: number | null) => void = () => {}
  let tick: () => void = () => {}
  const mgr = new LoginManager({
    paths: { home: "/home/u" },
    fileExists: () => true,
    spawnLogin: () => ({
      onStdout: () => {},
      onExit: (cb) => { exitCb = cb },
      kill: () => {},
      write: () => {},
    }),
    onChange: () => {},
    setInterval: (fn) => { tick = fn; return 1 },
    clearInterval: () => {},
  })

  mgr.start("claude")
  tick()
  tick()
  expect(mgr.get("claude")?.phase).toBe("starting")

  exitCb(0)
  expect(mgr.get("claude")?.phase).toBe("success")
})

test("reauth: already-authed agent exits non-zero → failed", () => {
  let exitCb: (code: number | null) => void = () => {}
  const mgr = new LoginManager({
    paths: { home: "/home/u" },
    fileExists: () => true,
    spawnLogin: () => ({
      onStdout: () => {},
      onExit: (cb) => { exitCb = cb },
      kill: () => {},
      write: () => {},
    }),
    onChange: () => {},
    setInterval: () => 1,
    clearInterval: () => {},
  })

  mgr.start("claude")
  exitCb(1)
  expect(mgr.get("claude")?.phase).toBe("failed")
})

test("Cursor login polling uses the supplied Windows APPDATA root", () => {
  let poll: () => void = () => {}
  const checked: string[] = []
  let present = false
  const mgr = new LoginManager({
    paths: {
      home: "C:\\Users\\u", appData: "D:\\Roaming", platform: "win32",
    },
    // First-time login: credential absent at start so poll can succeed when it appears.
    // (already-authed reauth mode ignores poll success — see reauth tests above.)
    fileExists: (path) => { checked.push(path); return present },
    spawnLogin: () => ({ onStdout: () => {}, onExit: () => {}, kill: () => {}, write: () => {} }),
    onChange: () => {}, setInterval: (fn) => { poll = fn; return 1 }, clearInterval: () => {},
  })
  mgr.start("cursor")
  present = true
  poll()
  expect(checked).toContain("D:\\Roaming\\cursor\\auth.json")
  expect(mgr.get("cursor")?.phase).toBe("success")
})

// OpenCode's Console (Go) sign-in: `opencode console login` prints a device link + code and may exit 0 even when the
// code expired, and an auth.json from an API key already exists. Only a NEW Console sign-in in opencode.db is success.
function openCodeHarness(probe: () => string | null) {
  let outCb: (c: string) => void = () => {}
  let exitCb: (code: number | null) => void = () => {}
  let tick: () => void = () => {}
  const mgr = new LoginManager({
    paths: { home: "/home/u" },
    fileExists: () => true,
    signInProbe: (kind) => (kind === "opencode" ? probe : undefined),
    spawnLogin: () => ({ onStdout: (cb) => { outCb = cb }, onExit: (cb) => { exitCb = cb }, kill: () => {}, write: () => {} }),
    onChange: () => {},
    setInterval: (fn) => { tick = fn; return 1 },
    clearInterval: () => {},
  })
  return { mgr, out: (c: string) => outCb(c), exit: (c: number | null) => exitCb(c), tick: () => tick() }
}

test("opencode: the device link and code come from `opencode console login`", () => {
  const h = openCodeHarness(() => null)
  h.mgr.start("opencode")
  h.out("\x1b[0m\n┌  Log in\n│\n●  Go to: https://opencode.ai/console/device?user_code=QLCP-HTDB&client_id=opencode-cli\n│\n●  Enter code: QLCP-HTDB\n")
  expect(h.mgr.get("opencode")).toMatchObject({
    phase: "awaiting_user",
    url: "https://opencode.ai/console/device?user_code=QLCP-HTDB&client_id=opencode-cli",
    code: "QLCP-HTDB",
  })
})

test("opencode: an exit 0 without a new Console sign-in is a failure, even with an API key in auth.json", () => {
  const h = openCodeHarness(() => "acc1:old")
  h.mgr.start("opencode")
  h.exit(0)
  expect(h.mgr.get("opencode")?.phase).toBe("failed")
})

test("opencode: a changed Console sign-in is success, while the CLI still runs", () => {
  let fp: string | null = null
  const h = openCodeHarness(() => fp)
  h.mgr.start("opencode")
  h.tick()
  expect(h.mgr.get("opencode")?.phase).toBe("starting")
  fp = "acc1:new"
  h.tick()
  expect(h.mgr.get("opencode")?.phase).toBe("success")
})
