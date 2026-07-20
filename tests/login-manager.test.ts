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

test("sendCode forwards input to the session", () => {
  let written = ""
  let outCb: (c: string) => void = () => {}
  const mgr = new LoginManager({
    paths: { home: "/home/u" }, fileExists: () => false,
    spawnLogin: () => ({
      onStdout: (cb: (c: string) => void) => { outCb = cb },
      onExit: () => {},
      kill: () => {},
      write: (data: string) => { written += data },
    }),
    onChange: () => {}, setInterval: () => 1, clearInterval: () => {},
  })
  mgr.start("claude")
  outCb("https://auth.example.com/device")
  mgr.sendCode("claude", "MYCODE")
  expect(written).toBe("MYCODE\n")
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
