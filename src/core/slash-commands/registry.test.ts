import { test, expect } from "bun:test"
import { CommandRegistry, type RegistrySession } from "./registry"
import type { CommandStore } from "./store"
import { agentCommand, type AgentCommandList, type AgentCommandProvider } from "./types"
import type { AgentKind } from "../agents/types"

const CONTROL = ["spawn", "model", "rename", "mute", "stop", "kill"]

const list = (names: string[], project?: string[]): AgentCommandList => ({
  commands: names.map((name) => agentCommand({ name, sigil: "/" })),
  ...(project ? { projectIds: project.map((n) => `agent:${n}`) } : {}),
})

/** In-memory CommandStore; `latest` is the last folder saved for the agent. */
function memoryStore(seed: Array<[AgentKind, string, AgentCommandList]> = []) {
  const rows = new Map<string, AgentCommandList>()
  const order: Array<[AgentKind, string]> = []
  const saves: Array<[AgentKind, string[]]> = []
  const store: CommandStore = {
    load: (kind, workdir) => rows.get(`${kind}\0${workdir}`),
    latest: (kind) => {
      const last = [...order].reverse().find(([k]) => k === kind)
      return last ? rows.get(`${last[0]}\0${last[1]}`) : undefined
    },
    save: (kind, workdirs, l) => {
      saves.push([kind, workdirs])
      for (const w of workdirs) { rows.set(`${kind}\0${w}`, l); order.push([kind, w]) }
    },
  }
  for (const [k, w, l] of seed) store.save(k, [w], l)
  saves.length = 0
  return { store, saves }
}

function provider(answer: () => AgentCommandList | undefined, onCall: () => void = () => {}): AgentCommandProvider {
  return { kind: "claude", async list() { onCall(); return answer() } }
}

const session = (extra: Partial<RegistrySession> = {}): RegistrySession => ({ name: "s1", kind: "claude", workdir: "/w/a", muted: false, ...extra })

test("a session shows the control commands, then what its agent reported", async () => {
  const { store } = memoryStore()
  const reg = new CommandRegistry({ providers: { claude: provider(() => list(["verify"])) }, resolveSession: () => session(), store })
  expect(reg.get("s1").map((c) => c.name)).toEqual(CONTROL)
  expect(reg.isResolved("s1")).toBe(false)
  await reg.refresh("s1")
  expect(reg.get("s1").map((c) => c.name)).toEqual([...CONTROL, "verify"])
  expect(reg.isResolved("s1")).toBe(true)
})

test("a reported list is stored for the session's folder and its repository", async () => {
  const { store, saves } = memoryStore()
  const reg = new CommandRegistry({ providers: { claude: provider(() => list(["verify"])) }, resolveSession: () => session({ workdir: "/w/tree", repoRoot: "/w/repo" }), store })
  await reg.refresh("s1")
  expect(saves).toEqual([["claude", ["/w/tree", "/w/repo"]]])
})

test("before its agent reports, a session shows the list stored for its folder", async () => {
  const { store } = memoryStore([["claude", "/w/a", list(["stored"])]])
  const reg = new CommandRegistry({ providers: { claude: provider(() => undefined) }, resolveSession: () => session(), store })
  expect(reg.isResolved("s1")).toBe(true)
  expect(reg.get("s1").map((c) => c.name)).toEqual([...CONTROL, "stored"])
  await reg.refresh("s1") // the agent has not reported: the stored list stays
  expect(reg.get("s1").map((c) => c.name)).toEqual([...CONTROL, "stored"])
})

test("a folder never used with the agent borrows its latest list minus another folder's own commands", () => {
  const { store } = memoryStore([["claude", "/w/other", list(["verify", "their-proj"], ["their-proj"])]])
  const reg = new CommandRegistry({ providers: { claude: provider(() => undefined) }, resolveSession: () => session({ workdir: "/w/new" }), store })
  expect(reg.get("s1").map((c) => c.name)).toEqual([...CONTROL, "verify"])
})

test("no fallback from an agent that does not mark its folder's own commands", () => {
  const { store } = memoryStore([["claude", "/w/other", list(["simplify", "their-cmd"])]])
  const reg = new CommandRegistry({ providers: { claude: provider(() => undefined) }, resolveSession: () => session({ workdir: "/w/new" }), store })
  expect(reg.get("s1").map((c) => c.name)).toEqual(CONTROL)
  expect(reg.isResolved("s1")).toBe(false)
})

test("a refresh asked while one runs runs once more afterwards, never more", async () => {
  let calls = 0
  let release: () => void = () => {}
  const { store } = memoryStore()
  const reg = new CommandRegistry({
    providers: { claude: { kind: "claude", async list() { calls++; if (calls === 1) await new Promise<void>((r) => { release = r }); return list([`v${calls}`]) } } },
    resolveSession: () => session(),
    store,
  })
  const first = reg.refresh("s1")
  const second = reg.refresh("s1")
  const third = reg.refresh("s1")
  release()
  await Promise.all([first, second, third])
  expect(calls).toBe(2)
  expect(reg.get("s1").map((c) => c.name)).toEqual([...CONTROL, "v2"])
})

test("onChange fires with the merged list after a refresh", async () => {
  const seen: string[][] = []
  const { store } = memoryStore()
  const reg = new CommandRegistry({
    providers: { claude: provider(() => list(["verify"])) },
    resolveSession: () => session(),
    store,
    onChange: (_n, cmds) => seen.push(cmds.map((c) => c.name)),
  })
  await reg.refresh("s1")
  expect(seen).toEqual([[...CONTROL, "verify"]])
})

test("the launcher takes an exact answer first, then the folder's stored list, then the fallback", async () => {
  const { store } = memoryStore([["claude", "/w/a", list(["stored"])], ["claude", "/w/b", list(["latest", "b-proj"], ["b-proj"])]])
  let exact: AgentCommandList | undefined = list(["exact"])
  let calls = 0
  const reg = new CommandRegistry({ providers: { claude: provider(() => exact, () => calls++) }, resolveSession: () => undefined, store })
  expect(await reg.preview({ kind: "claude", workdir: "/w/a" })).toEqual({ commands: list(["exact"]).commands, resolved: true })
  exact = undefined
  expect((await reg.preview({ kind: "claude", workdir: "/w/a" })).commands.map((c) => c.name)).toEqual(["stored"])
  expect((await reg.preview({ kind: "claude", workdir: "/w/new" })).commands.map((c) => c.name)).toEqual(["latest"])
  expect(await reg.preview({ kind: "cursor", workdir: "/w/new" })).toEqual({ commands: [], resolved: true })
  expect(calls).toBe(3) // one ask per preview, none in the background
})

test("the launcher's context reaches the provider", async () => {
  const ctxs: unknown[] = []
  const { store } = memoryStore()
  const grok: AgentCommandProvider = { kind: "grok", async list(ctx) { ctxs.push(ctx); return undefined } }
  const reg = new CommandRegistry({ providers: { grok }, resolveSession: () => undefined, store })
  await reg.preview({ kind: "grok", workdir: "/w/a", agentContext: { commands: [] } })
  expect(ctxs).toEqual([{ sessionName: "preview:grok", workdir: "/w/a", agentContext: { commands: [] } }])
})

test("remove forgets the session's live list", async () => {
  const { store } = memoryStore()
  let answer: AgentCommandList | undefined = list(["live"])
  const reg = new CommandRegistry({ providers: { claude: provider(() => answer) }, resolveSession: () => session({ workdir: "/w/none" }), store })
  await reg.refresh("s1")
  reg.remove("s1")
  answer = undefined
  // The stored copy (saved when it reported) is what remains.
  expect(reg.get("s1").map((c) => c.name)).toEqual([...CONTROL, "live"])
})

test("a session that reuses a name never sees the live list of the session that had it", async () => {
  const { store } = memoryStore()
  let current = session({ id: "id-1", workdir: "/w/a" })
  let answer: AgentCommandList | undefined = list(["old"]) // no projectIds: no fallback for other folders
  const reg = new CommandRegistry({ providers: { claude: provider(() => answer) }, resolveSession: () => current, store })
  await reg.refresh("s1")
  current = session({ id: "id-2", workdir: "/w/b" })
  answer = undefined
  await reg.refresh("s1")
  expect(reg.get("s1").map((c) => c.name)).toEqual(CONTROL)
})

test("a session removed while its refresh runs stays removed", async () => {
  const { store } = memoryStore()
  let release: () => void = () => {}
  const changes: string[] = []
  const reg = new CommandRegistry({
    providers: { claude: { kind: "claude", async list() { await new Promise<void>((r) => { release = r }); return list(["late"]) } } },
    resolveSession: () => session({ workdir: "/w/none" }),
    store,
    onChange: (n) => changes.push(n),
  })
  const running = reg.refresh("s1")
  await Promise.resolve()
  reg.remove("s1")
  release()
  await running
  expect(changes).toEqual([])
  expect(reg.isResolved("s1")).toBe(false)
  expect(reg.get("s1").map((c) => c.name)).toEqual(CONTROL)
})

test("an unchanged list is not broadcast again", async () => {
  const { store } = memoryStore()
  const seen: number[] = []
  const reg = new CommandRegistry({
    providers: { claude: provider(() => list(["verify"])) },
    resolveSession: () => session(),
    store,
    onChange: (_n, cmds) => seen.push(cmds.length),
  })
  await reg.refresh("s1")
  await reg.refresh("s1")
  await reg.refresh("s1")
  expect(seen).toEqual([CONTROL.length + 1])
})
