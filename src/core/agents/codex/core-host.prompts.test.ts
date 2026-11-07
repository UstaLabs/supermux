import { afterEach, beforeEach, describe, expect, test } from "bun:test"
import { existsSync, mkdtempSync, readFileSync, readdirSync, rmSync, statSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import type { AgentDriver, AgentRuntime } from "../../../../packages/supermux-core/src/index.js"
import type { CodexOptions } from "../../../../packages/supermux-core/src/codex/index.js"
import { trustedHostBinary } from "../../../../packages/supermux-core/src/environment/index.js"
import { codexReadRules, type ReadProgramResolver } from "../../../../packages/supermux-core/src/environment/codex.js"
import { inboxFilePath } from "../../../shared/paths"
import { createCodexCoreHost } from "./core-host"

// Hermetic credentials: the host's prepare step takes OPENAI_API_KEY first and only falls back
// to the user's own login files without one: files CI does not have, and that a developer
// machine must not have copied (or written back) by a test. A fake key per test, restored after.
const CREDENTIAL_KEYS = ["OPENAI_API_KEY"] as const
const savedKeys = new Map<string, string | undefined>()
beforeEach(() => {
  for (const k of CREDENTIAL_KEYS) { savedKeys.set(k, process.env[k]); process.env[k] = "test-key" }
})
afterEach(() => {
  for (const k of CREDENTIAL_KEYS) {
    const prev = savedKeys.get(k)
    if (prev === undefined) delete process.env[k]
    else process.env[k] = prev
  }
})

const dirs: string[] = []
afterEach(() => {
  for (const d of dirs.splice(0)) rmSync(d, { recursive: true, force: true })
})

/** The session's own working directory, beside its state directory: the core refuses a cwd that is the state directory. */
function workdir(): string {
  const work = mkdtempSync(join(tmpdir(), "codex-work-"))
  dirs.push(work)
  return work
}

function fakeDriver(): AgentDriver {
  return {
    id: "codex",
    async open() {
      const runtime: AgentRuntime = {
        agentSessionId: "n1",
        capabilities: { resume: true, steer: true, fork: true, detach: true, configure: true },
        async prompt() { return { stopReason: "end_turn" } },
        async interrupt() {},
        async close() {},
      }
      return runtime
    },
  }
}

test("codex ask mode uses untrusted/workspace-write", async () => {
  const captured: CodexOptions[] = []
  const dir = mkdtempSync(join(tmpdir(), "codex-host-"))
  dirs.push(dir)
  const work = workdir()
  const host = createCodexCoreHost({
    stateDirectory: dir,
    driverFactory: (options) => {
      captured.push(options)
      return fakeDriver()
    },
  })
  const extra = {
    sessionHome: dir, sessionName: "s", sessionId: "id1", workdir: work, cwd: work, permissionMode: "ask",
  }
  const handle = host.register({ id: "id1", env: {}, extra })
  await handle.start({ cwd: work })
  expect(captured[0]?.approvalPolicy).toBe("untrusted")
  expect(captured[0]?.sandbox).toBe("workspace-write")
  expect(captured[0]?.permissions).toEqual({ kind: "codex", approvalPolicy: "untrusted", sandbox: "workspace-write" })
  await handle.stop({ mode: "shutdown" })
  await host.close({ agents: "shutdown" })
})

test("codex default mode uses never/full-access", async () => {
  const captured: CodexOptions[] = []
  const dir = mkdtempSync(join(tmpdir(), "codex-host-"))
  dirs.push(dir)
  const work = workdir()
  const host = createCodexCoreHost({
    stateDirectory: dir,
    driverFactory: (options) => {
      captured.push(options)
      return fakeDriver()
    },
  })
  const extra = {
    sessionHome: dir, sessionName: "s", sessionId: "id2", workdir: work, cwd: work,
  }
  const handle = host.register({ id: "id2", env: {}, extra })
  await handle.start({ cwd: work })
  expect(captured[0]?.approvalPolicy).toBe("never")
  expect(captured[0]?.sandbox).toBe("danger-full-access")
  expect(captured[0]?.permissions).toEqual({ kind: "codex", approvalPolicy: "never", sandbox: "danger-full-access" })
  await handle.stop({ mode: "shutdown" })
  await host.close({ agents: "shutdown" })
})

/** A program lookup that finds only `names`, each at /fake/bin/<name>: injected into the host, so nothing is read from the disk. */
function fakeLookup(names: string[] = ["cat", "head", "tail", "wc"]): { resolve: ReadProgramResolver; programs: Record<string, string> } {
  const programs = Object.fromEntries(names.map(name => [name, `/fake/bin/${name}`]))
  return { resolve: name => programs[name], programs }
}

/** Starts Codex sessions `ids` on ONE host: each one's home, the instructions it launched with, and what the logger wrote to stderr meanwhile. */
async function startSessions(ids: string[], resolveReadProgram?: ReadProgramResolver): Promise<{ homes: string[]; instructions: string[]; logged: string[] }> {
  const homes: string[] = []
  const instructions: string[] = []
  const logged: string[] = []
  const write = process.stderr.write
  process.stderr.write = ((chunk: string | Uint8Array) => { logged.push(String(chunk)); return true }) as typeof process.stderr.write
  try {
    const driver = (): AgentDriver => ({
      id: "codex",
      async open(ctx) {
        instructions.push(ctx.sessionContext?.instructions ?? "")
        return fakeDriver().open(ctx)
      },
    })
    const host = createCodexCoreHost({ stateDirectory: mkdtempSync(join(tmpdir(), "codex-state-")), ...(resolveReadProgram ? { resolveReadProgram } : {}), driverFactory: driver })
    for (const id of ids) {
      const home = mkdtempSync(join(tmpdir(), "codex-host-"))
      dirs.push(home)
      homes.push(home)
      const work = workdir()
      const handle = host.register({ id, env: {}, extra: { sessionHome: home, sessionName: `s-${id}`, sessionId: id, workdir: work, cwd: work, permissionMode: "ask" } })
      await handle.start({ cwd: work })
      await handle.stop({ mode: "shutdown" })
    }
    await host.close({ agents: "shutdown" })
  } finally { process.stderr.write = write }
  return { homes, instructions, logged }
}

describe.skipIf(process.platform === "win32")("codex shell reads of the own inbox file", () => {
  test("exec-policy rules in the session's CODEX_HOME name the host's binaries by absolute path, and the preamble offers exactly those", async () => {
    const { resolve, programs } = fakeLookup()
    const { homes, instructions, logged } = await startSessions(["id-rules-1", "id-rules-2"], resolve)
    for (const [i, id] of ["id-rules-1", "id-rules-2"].entries()) {
      const rules = join(homes[i]!, "rules", `supermux-inbox-${id}.rules`)
      const file = inboxFilePath(id)
      expect(readFileSync(rules, "utf8")).toBe(codexReadRules([file], programs))
      expect(statSync(rules).mode & 0o777).toBe(0o600)
      expect(readdirSync(join(homes[i]!, "rules"))).toEqual([`supermux-inbox-${id}.rules`])
      // The preamble names the same absolute programs, never the bare ones (a bare name is whatever PATH finds, and no rule allows it).
      expect(instructions[i]).toContain(`From a shell, read it with exactly \`${programs.cat} ${file}\` or \`${programs.tail} ${file}\`.`)
      expect(instructions[i]).not.toContain(`\`cat ${file}\``)
      expect(instructions[i]).not.toContain(`\`tail ${file}\``)
    }
    // Nothing of the first session leaks into the second: its own home, its own file.
    expect(readFileSync(join(homes[1]!, "rules", "supermux-inbox-id-rules-2.rules"), "utf8")).not.toContain("id-rules-1")
    expect(logged.filter(line => line.includes("codex_inbox_"))).toEqual([])
  })

  test("with no binary that can be trusted there are no rules, the preamble says the shell may ask, and the host says so once", async () => {
    const { homes, instructions, logged } = await startSessions(["id-nobin-1", "id-nobin-2"], () => undefined)
    for (const home of homes) expect(existsSync(join(home, "rules"))).toBe(false)
    for (const [i, id] of ["id-nobin-1", "id-nobin-2"].entries()) {
      // Codex has no file-read tool: it reads through its shell, which may ask. Say that, and never name a command as if it were allowed.
      expect(instructions[i]).toContain(`Read it with \`cat ${inboxFilePath(id)}\`; your shell may ask for approval.`)
      expect(instructions[i]).not.toContain("From a shell")
      expect(instructions[i]).not.toContain("file-read tool")
    }
    const warnings = logged.filter(line => line.includes("codex_inbox_read_rule_omitted"))
    expect(warnings).toHaveLength(1) // two sessions, one warning
    expect(warnings[0]).toContain("[WARN]")
    expect(warnings[0]).toContain('"programs":["cat","head","tail","wc"]')
  })

  test("a binary that is missing gets no rule and the preamble offers only what is allowed; each missing program is said once", async () => {
    const { resolve, programs } = fakeLookup(["cat", "tail"])
    const { homes, instructions, logged } = await startSessions(["id-part-1", "id-part-2"], resolve)
    const file = inboxFilePath("id-part-1")
    const rules = readFileSync(join(homes[0]!, "rules", "supermux-inbox-id-part-1.rules"), "utf8")
    expect(rules).toBe(codexReadRules([file], programs))
    expect(rules).not.toMatch(/head|wc/)
    expect(instructions[0]).toContain(`From a shell, read it with exactly \`${programs.cat} ${file}\` or \`${programs.tail} ${file}\`.`)
    const warnings = logged.filter(line => line.includes("codex_inbox_read_rule_omitted"))
    expect(warnings).toHaveLength(1)
    expect(warnings[0]).toContain('"programs":["head","wc"]')
  })

  test("by default the host's own binaries are used: the system's, found and checked, or no rules at all", async () => {
    const { homes, instructions } = await startSessions(["id-real-1"])
    const real = ["cat", "head", "tail", "wc"].flatMap(name => { const path = trustedHostBinary(name); return path ? [[name, path] as const] : [] })
    const rules = join(homes[0]!, "rules", "supermux-inbox-id-real-1.rules")
    if (real.length === 0) {
      expect(existsSync(rules)).toBe(false)
      return
    }
    const text = readFileSync(rules, "utf8")
    for (const [name, path] of real) {
      expect(path.startsWith("/")).toBe(true)
      expect(text).toContain(`host_executable(name = "${name}", paths = ["${path}"])`)
    }
    // Whatever was trusted is what the rules name, and no program is named by a bare name.
    expect(text).not.toMatch(/pattern = \[\[?"(cat|head|tail|wc)"/)
    expect(instructions[0]).toContain(real.some(([name]) => name === "cat") ? "From a shell, read it with exactly" : "file-read tool")
  })
})
