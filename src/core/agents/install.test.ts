import { expect, test, describe } from "bun:test"
import { EventEmitter } from "events"
import type { ChildProcess } from "child_process"
import {
  INSTALL_RECIPES, POWERSHELL_PREAMBLE, createInstallManager, installCommand, installRecipeFor, startInstall,
  type InstallDeps,
} from "./install"
import { AGENT_KINDS } from "../../shared/agents"
import type { BuiltinInstallDeps } from "./install-builtin"

function fakeChild(): ChildProcess {
  const c = new EventEmitter() as any
  c.stdout = new EventEmitter()
  c.stderr = new EventEmitter()
  c.pid = 999
  c.kill = () => {}
  return c as ChildProcess
}

/** The child ended: `exit`, then `close` once its output is drained. */
function end(child: ChildProcess, code: number) {
  child.emit("exit", code, null)
  child.emit("close", code, null)
}

const WIN_ENV = {
  SystemRoot: "C:\\WINDOWS",
  Path: "C:\\WINDOWS\\system32",
  USERPROFILE: "C:\\Users\\t",
  LOCALAPPDATA: "C:\\Users\\t\\AppData\\Local",
}

describe("recipe selection", () => {
  test("macOS and Linux run the vendors' shell installers through bash", () => {
    for (const platform of ["darwin", "linux"] as const) {
      for (const kind of AGENT_KINDS) {
        const r = installRecipeFor(kind, platform)
        expect("unsupported" in r).toBe(false)
        expect((r as any).shell).toBe("bash")
      }
      expect(installRecipeFor("claude", platform)).toEqual({ shell: "bash", script: "curl -fsSL https://claude.ai/install.sh | bash" })
    }
  })

  test("no recipe needs node or npm, and codex is the standalone non-interactive installer", () => {
    for (const os of ["posix", "win32"] as const) {
      for (const kind of AGENT_KINDS) expect(INSTALL_RECIPES[os][kind]?.script ?? "").not.toMatch(/\bnpm\b|\bnpx\b/)
    }
    expect(INSTALL_RECIPES.posix.codex!.script).toBe("curl -fsSL https://chatgpt.com/codex/install.sh | CODEX_NON_INTERACTIVE=1 sh")
    expect(INSTALL_RECIPES.posix.opencode!.script).toContain("--no-modify-path")
  })

  test("Windows: PowerShell scripts for claude/codex/cursor, builtin for opencode/grok", () => {
    expect(installRecipeFor("claude", "win32")).toEqual({ shell: "powershell", script: "irm https://claude.ai/install.ps1 | iex" })
    expect(installRecipeFor("codex", "win32")).toEqual({
      shell: "powershell",
      script: "$env:CODEX_NON_INTERACTIVE='1'; irm https://chatgpt.com/codex/install.ps1 | iex",
    })
    expect(installRecipeFor("cursor", "win32")).toEqual({ shell: "powershell", script: "irm 'https://cursor.com/install?win32=true' | iex" })
    expect(installRecipeFor("opencode", "win32")).toEqual({ shell: "builtin", script: "opencode-windows" })
    expect(installRecipeFor("grok", "win32")).toEqual({ shell: "builtin", script: "grok-windows" })
  })

  test("an OS without recipes is a clear 'not supported', not an attempt", () => {
    const r = installRecipeFor("grok", "freebsd")
    expect(r).toEqual({ unsupported: expect.stringContaining("not supported") })
  })
})

describe("the command line", () => {
  test("PowerShell: the pinned System32 path, no profile, non-interactive, bypass, -Command <preamble+script>", () => {
    const recipe = INSTALL_RECIPES.win32.claude!
    const { cmd, args } = installCommand(recipe, WIN_ENV)
    expect(cmd).toBe("C:\\WINDOWS\\System32\\WindowsPowerShell\\v1.0\\powershell.exe")
    expect(args).toEqual(["-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-Command", POWERSHELL_PREAMBLE + recipe.script])
  })

  test("PowerShell path falls back to C:\\Windows without SystemRoot", () => {
    expect(installCommand(INSTALL_RECIPES.win32.cursor!, {}).cmd).toBe("C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell.exe")
  })

  test("Windows spawn: powershell, stdin ignored, hidden, one Path key with the agent dirs", () => {
    let captured: any
    const spawn = (cmd: string, args: string[], opts: any) => {
      captured = { cmd, args, opts }
      return fakeChild()
    }
    startInstall("codex", { spawn, isInstalled: () => true, platform: "win32", env: WIN_ENV, home: "C:\\Users\\t" })
    expect(captured.cmd).toBe("C:\\WINDOWS\\System32\\WindowsPowerShell\\v1.0\\powershell.exe")
    expect(captured.opts.stdio[0]).toBe("ignore")
    expect(captured.opts.windowsHide).toBe(true)
    const pathKeys = Object.keys(captured.opts.env).filter((k) => k.toLowerCase() === "path")
    expect(pathKeys).toEqual(["Path"])
    const path = captured.opts.env.Path.split(";")
    expect(path).toContain("C:\\Users\\t\\AppData\\Local\\Programs\\OpenAI\\Codex\\bin")
    expect(path).toContain("C:\\WINDOWS\\system32")
    expect(path).not.toContain("/opt/homebrew/bin")
    expect(captured.opts.env.CODEX_NON_INTERACTIVE).toBe("1")
  })

  test("macOS/Linux spawn: bash -lc <recipe> with stdin ignored and a non-interactive env", () => {
    let captured: any
    const spawn = (cmd: string, args: string[], opts: any) => {
      captured = { cmd, args, opts }
      return fakeChild()
    }
    startInstall("opencode", { spawn, isInstalled: () => true, platform: "linux", env: { PATH: "/usr/bin" }, home: "/home/u" })
    expect(captured.cmd).toBe("bash")
    expect(captured.args).toEqual(["-lc", INSTALL_RECIPES.posix.opencode!.script])
    expect(captured.opts.stdio[0]).toBe("ignore")
    expect(captured.opts.env.CI).toBe("1")
    expect(captured.opts.env.NONINTERACTIVE).toBe("1")
    expect(captured.opts.env.npm_config_yes).toBe("true")
    expect(captured.opts.env.PATH.split(":")).toContain("/home/u/.local/bin")
  })
})

describe("job lifecycle", () => {
  const linux: Partial<InstallDeps> = { platform: "linux", env: { PATH: "/usr/bin" }, home: "/home/u" }

  test("done when the installer exits 0 and the binary is now detected", async () => {
    const child = fakeChild()
    const { job, done } = startInstall("opencode", { ...linux, spawn: () => child, isInstalled: () => true })
    expect(job.state).toBe("running")
    ;(child.stdout as any).emit("data", "installing opencode...\n")
    end(child, 0)
    await done
    expect(job.state).toBe("done")
    expect(job.exitCode).toBe(0)
    expect(job.log).toContain("installing opencode...")
    expect(job.error).toBeUndefined()
  })

  test("output that arrives after exit (before close) still lands in the log", async () => {
    const child = fakeChild()
    const { job, done } = startInstall("claude", { ...linux, spawn: () => child, isInstalled: () => false })
    child.emit("exit", 2, null)
    ;(child.stderr as any).emit("data", "curl: (6) Could not resolve host\n")
    child.emit("close", 2, null)
    await done
    expect(job.log).toContain("Could not resolve host")
    expect(job.exitCode).toBe(2)
    expect(job.error).toBeUndefined() // the exit code says it
  })

  test("the PATH refresh runs before the binary is re-probed", async () => {
    const child = fakeChild()
    const order: string[] = []
    const { job, done } = startInstall("codex", {
      ...linux,
      spawn: () => child,
      refreshPath: async () => { order.push("refresh") },
      isInstalled: () => { order.push("probe"); return true },
    })
    end(child, 0)
    await done
    expect(order).toEqual(["refresh", "probe"])
    expect(job.state).toBe("done")
  })

  test("failed when the installer exits 0 but the binary is still missing", async () => {
    const child = fakeChild()
    const { job, done } = startInstall("opencode", { ...linux, spawn: () => child, isInstalled: () => false })
    end(child, 0)
    await done
    expect(job.state).toBe("failed")
    expect(job.error).toContain("can't find")
  })

  test("failed on a non-zero exit", async () => {
    const child = fakeChild()
    const { job, done } = startInstall("codex", { ...linux, spawn: () => child, isInstalled: () => true })
    end(child, 1)
    await done
    expect(job.state).toBe("failed")
    expect(job.exitCode).toBe(1)
  })

  test("a shell that can't start (ENOENT) is a failed job, not a crash", async () => {
    const child = fakeChild()
    const { job, done } = startInstall("claude", { ...linux, spawn: () => child, isInstalled: () => false })
    child.emit("error", Object.assign(new Error("spawn bash ENOENT"), { code: "ENOENT" }))
    await done
    expect(job.state).toBe("failed")
    expect(job.exitCode).toBeNull()
    expect(job.log).toContain("ENOENT")
    expect(job.error).toContain("couldn't start bash")
  })

  test("a spawn that throws synchronously is a failed job too", async () => {
    const { job, done } = startInstall("claude", {
      ...linux,
      spawn: () => { throw new Error("EACCES") },
      isInstalled: () => false,
    })
    await done
    expect(job.state).toBe("failed")
    expect(job.error).toContain("EACCES")
  })

  test("unsupported OS: an immediately failed job that says so, nothing spawned", async () => {
    let spawned = false
    const { job, done } = startInstall("cursor", {
      platform: "aix",
      spawn: () => { spawned = true; return fakeChild() },
      isInstalled: () => false,
    })
    await done
    expect(spawned).toBe(false)
    expect(job.state).toBe("failed")
    expect(job.error).toContain("not supported")
    expect(job.log).toContain("not supported")
  })

  test("builtin installers run in-process with the injected machine and log into the job", async () => {
    const calls: string[] = []
    const builtin = {
      env: WIN_ENV,
      arch: "x64",
      fetch: (async () => { calls.push("fetch"); return new Response("nope", { status: 503 }) }) as unknown as typeof fetch,
      sha256: () => "",
      extract: async () => {},
      fs: { exists: () => false, mkdir: () => {}, write: () => {}, rename: () => {}, remove: () => {} },
      readUserPath: async () => null,
      writeUserPath: async () => {},
    } satisfies Omit<BuiltinInstallDeps, "log">
    let spawned = false
    const { job, done } = startInstall("grok", {
      platform: "win32",
      builtin,
      spawn: () => { spawned = true; return fakeChild() },
      isInstalled: () => false,
    })
    await done
    expect(spawned).toBe(false)
    expect(calls).toEqual(["fetch"])
    expect(job.state).toBe("failed")
    expect(job.exitCode).toBe(1)
    expect(job.log).toContain("HTTP 503")
  })
})

test("manager: get returns undefined before any start", () => {
  const mgr = createInstallManager({ spawn: () => fakeChild(), isInstalled: () => true, platform: "linux" })
  expect(mgr.get("codex")).toBeUndefined()
})

test("manager: no double-start while running, restartable after it finishes", async () => {
  const children = [fakeChild(), fakeChild()]
  let i = 0
  const mgr = createInstallManager({ spawn: () => children[i++]!, isInstalled: () => true, platform: "linux" })

  const first = mgr.start("opencode")
  expect(first.alreadyRunning).toBe(false)
  expect(mgr.get("opencode")).toBe(first.job)
  // a second start while the first is still running is a no-op on the same job
  const second = mgr.start("opencode")
  expect(second.alreadyRunning).toBe(true)
  expect(second.job).toBe(first.job)

  // finish it → a fresh start spins up a new job
  end(children[0]!, 0)
  await new Promise((r) => setTimeout(r, 0))
  expect(first.job.state).toBe("done")
  const third = mgr.start("opencode")
  expect(third.alreadyRunning).toBe(false)
  expect(third.job).not.toBe(first.job)
})
