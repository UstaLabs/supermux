import { expect, test } from "bun:test"
import { EventEmitter } from "events"
import type { ChildProcess } from "child_process"
import { INSTALL_RECIPES, startInstall, createInstallManager } from "./install"

function fakeChild(): ChildProcess {
  const c = new EventEmitter() as any
  c.stdout = new EventEmitter()
  c.stderr = new EventEmitter()
  c.pid = 999
  c.kill = () => {}
  return c as ChildProcess
}

test("runs bash -lc <recipe> with stdin ignored and a non-interactive env", () => {
  let captured: any
  const spawn = (cmd: string, args: string[], opts: any) => {
    captured = { cmd, args, opts }
    return fakeChild()
  }
  startInstall("opencode", { spawn, isInstalled: () => true })

  expect(captured.cmd).toBe("bash")
  expect(captured.args).toEqual(["-lc", INSTALL_RECIPES.opencode])
  // stdin is "ignore" → never a TTY, so installers take their non-interactive path
  expect(captured.opts.stdio[0]).toBe("ignore")
  expect(captured.opts.env.CI).toBe("1")
  expect(captured.opts.env.NONINTERACTIVE).toBe("1")
  expect(captured.opts.env.npm_config_yes).toBe("true")
})

test("marks done when the installer exits 0 and the binary is now detected", async () => {
  const child = fakeChild()
  const { job, done } = startInstall("opencode", { spawn: () => child, isInstalled: () => true })
  expect(job.state).toBe("running")
  ;(child.stdout as any).emit("data", "installing opencode...\n")
  child.emit("exit", 0, null)
  await done
  expect(job.state).toBe("done")
  expect(job.exitCode).toBe(0)
  expect(job.log).toContain("installing opencode...")
})

test("marks failed when the installer exits 0 but the binary is still missing", async () => {
  const child = fakeChild()
  const { job, done } = startInstall("opencode", { spawn: () => child, isInstalled: () => false })
  child.emit("exit", 0, null)
  await done
  expect(job.state).toBe("failed")
})

test("marks failed on a non-zero exit", async () => {
  const child = fakeChild()
  const { job, done } = startInstall("codex", { spawn: () => child, isInstalled: () => true })
  child.emit("exit", 1, null)
  await done
  expect(job.state).toBe("failed")
  expect(job.exitCode).toBe(1)
})

test("every agent kind has an install recipe", () => {
  for (const kind of ["claude", "codex", "cursor", "opencode"] as const) {
    expect(typeof INSTALL_RECIPES[kind]).toBe("string")
    expect(INSTALL_RECIPES[kind].length).toBeGreaterThan(0)
  }
})

test("manager: get returns undefined before any start", () => {
  const mgr = createInstallManager({ spawn: () => fakeChild(), isInstalled: () => true })
  expect(mgr.get("codex")).toBeUndefined()
})

test("manager: no double-start while running, restartable after it finishes", async () => {
  const children = [fakeChild(), fakeChild()]
  let i = 0
  const mgr = createInstallManager({ spawn: () => children[i++]!, isInstalled: () => true })

  const first = mgr.start("opencode")
  expect(first.alreadyRunning).toBe(false)
  expect(mgr.get("opencode")).toBe(first.job)
  // a second start while the first is still running is a no-op on the same job
  const second = mgr.start("opencode")
  expect(second.alreadyRunning).toBe(true)
  expect(second.job).toBe(first.job)

  // finish it → a fresh start spins up a new job
  children[0]!.emit("exit", 0, null)
  await new Promise((r) => setTimeout(r, 0))
  expect(first.job.state).toBe("done")
  const third = mgr.start("opencode")
  expect(third.alreadyRunning).toBe(false)
  expect(third.job).not.toBe(first.job)
})
