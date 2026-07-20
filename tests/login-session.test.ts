import { test, expect } from "bun:test"
import { LoginSession, type LoginProc, type LoginState } from "../src/core/agents/login/session"

function fakeProc() {
  let outCb: (c: string) => void = () => {}
  let exitCb: (c: number | null) => void = () => {}
  let killed = false
  const proc: LoginProc = {
    onStdout: (cb) => { outCb = cb },
    onExit: (cb) => { exitCb = cb },
    kill: () => { killed = true; exitCb(null) },
    write: () => {},
  }
  return { proc, emit: (s: string) => outCb(s), exit: (c: number | null) => exitCb(c), get killed() { return killed } }
}

test("transitions starting → awaiting_user when url+code parsed, → success when authed", () => {
  const states: LoginState[] = []
  let authed = false
  const f = fakeProc()
  let tick: () => void = () => {}
  const s = new LoginSession({
    kind: "codex",
    spawn: () => f.proc,
    parse: (out) => {
      const url = out.includes("URL ") ? "https://dev/url" : undefined
      const code = out.includes("CODE ") ? "AAAA-BBBB" : undefined
      return url || code ? { url, code } : null
    },
    isAuthed: () => authed,
    onChange: (st) => states.push({ ...st }),
    setInterval: (fn) => { tick = fn; return 1 },
    clearInterval: () => {},
  })
  s.start()
  expect(states.at(-1)!.phase).toBe("starting")
  f.emit("URL CODE here")
  expect(states.at(-1)!.phase).toBe("awaiting_user")
  expect(states.at(-1)!).toMatchObject({ url: "https://dev/url", code: "AAAA-BBBB" })
  authed = true
  tick()
  expect(states.at(-1)!.phase).toBe("success")
})

test("non-zero exit before success ⇒ failed with the stdout tail", () => {
  const states: LoginState[] = []
  const f = fakeProc()
  const s = new LoginSession({
    kind: "cursor", spawn: () => f.proc, parse: () => null, isAuthed: () => false,
    onChange: (st) => states.push({ ...st }), setInterval: () => 1, clearInterval: () => {},
  })
  s.start()
  f.emit("some error output")
  f.exit(1)
  expect(states.at(-1)!.phase).toBe("failed")
  expect(states.at(-1)!.error).toContain("some error output")
})

test("cancel() kills the proc and transitions to cancelled", () => {
  const states: LoginState[] = []
  const f = fakeProc()
  const s = new LoginSession({
    kind: "codex", spawn: () => f.proc, parse: () => null, isAuthed: () => false,
    onChange: (st) => states.push({ ...st }), setInterval: () => 1, clearInterval: () => {},
  })
  s.start()
  s.cancel()
  expect(f.killed).toBe(true)
  expect(states.at(-1)!.phase).toBe("cancelled")
})

test("alreadyAuthed: poll does NOT auto-succeed (reauth)", () => {
  const states: LoginState[] = []
  let tick: () => void = () => {}
  const f = fakeProc()
  const s = new LoginSession({
    kind: "claude", spawn: () => f.proc, parse: () => null,
    isAuthed: () => true,
    alreadyAuthed: true,
    onChange: (st) => states.push({ ...st }),
    setInterval: (fn) => { tick = fn; return 1 }, clearInterval: () => {},
  })
  s.start()
  tick()
  tick()
  expect(states.at(-1)!.phase).toBe("starting")
})

test("alreadyAuthed: exit code 0 → success (reauth completed)", () => {
  const states: LoginState[] = []
  const f = fakeProc()
  const s = new LoginSession({
    kind: "claude", spawn: () => f.proc, parse: () => null,
    isAuthed: () => true,
    alreadyAuthed: true,
    onChange: (st) => states.push({ ...st }),
    setInterval: () => 1, clearInterval: () => {},
  })
  s.start()
  f.exit(0)
  expect(states.at(-1)!.phase).toBe("success")
})

test("alreadyAuthed: non-zero exit → failed (reauth aborted)", () => {
  const states: LoginState[] = []
  const f = fakeProc()
  const s = new LoginSession({
    kind: "claude", spawn: () => f.proc, parse: () => null,
    isAuthed: () => true,
    alreadyAuthed: true,
    onChange: (st) => states.push({ ...st }),
    setInterval: () => 1, clearInterval: () => {},
  })
  s.start()
  f.emit("user cancelled")
  f.exit(1)
  expect(states.at(-1)!.phase).toBe("failed")
  expect(states.at(-1)!.error).toContain("user cancelled")
})
