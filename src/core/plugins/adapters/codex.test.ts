import { test, expect } from "bun:test"
import { mkdtempSync, mkdirSync, writeFileSync, rmSync, readFileSync, existsSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { EventEmitter } from "events"
import { CodexPluginAdapter, buildCodexMarketplace, marketplaceRootFor, codexPluginId, CODEX_MARKETPLACE_NAME, runCodexPluginCommand, runCodexPluginCommandSync } from "./codex"
import type { Plugin } from "../types"

function tmpRoot(): string {
  return mkdtempSync(join(tmpdir(), "codex-adapter-"))
}

function makePlugin(root: string, name: string, opts: { codexManifest?: boolean; manifestName?: string; enabled?: boolean; scopes?: Plugin["scopes"]; overrides?: Plugin["perSessionOverrides"] } = {}): Plugin {
  const dir = join(root, name)
  mkdirSync(dir, { recursive: true })
  if (opts.codexManifest ?? true) {
    mkdirSync(join(dir, ".codex-plugin"), { recursive: true })
    writeFileSync(join(dir, ".codex-plugin", "plugin.json"), JSON.stringify({ name: opts.manifestName ?? name }))
  }
  return {
    name,
    source: { type: "local", path: dir },
    enabled: opts.enabled ?? true,
    scopes: opts.scopes ?? ["codex"],
    perSessionOverrides: opts.overrides,
    dir,
  }
}

test("isCompatible is true only when .codex-plugin/plugin.json exists", () => {
  const root = tmpRoot()
  try {
    const a = new CodexPluginAdapter()
    expect(a.isCompatible(makePlugin(root, "good", { codexManifest: true }))).toBe(true)
    expect(a.isCompatible(makePlugin(root, "bad", { codexManifest: false }))).toBe(false)
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})

test("buildCodexMarketplace maps plugins to AVAILABLE local entries with paths relative to the marketplace root", () => {
  const root = tmpRoot()
  try {
    const sp = makePlugin(root, "superpowers")
    const core = makePlugin(root, "mux-core")
    // Plugins live directly under `root`; with `root` as the marketplace root,
    // their relative source paths are "./<name>".
    const market = buildCodexMarketplace([sp, core], root)
    expect(market.name).toBe(CODEX_MARKETPLACE_NAME)
    expect(market.plugins).toEqual([
      { name: "superpowers", source: { source: "local", path: "./superpowers" }, policy: { installation: "AVAILABLE" } },
      { name: "mux-core", source: { source: "local", path: "./mux-core" }, policy: { installation: "AVAILABLE" } },
    ])
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})

test("marketplaceRootFor returns the dir two levels above .agents/plugins/marketplace.json", () => {
  expect(marketplaceRootFor("/home/u/.agents/plugins/marketplace.json")).toBe("/home/u")
})

test("codex uses the MANIFEST name, not the registry/dir name (mux-core → mux)", () => {
  const root = tmpRoot()
  try {
    // Registry/dir name is "mux-core" but the manifest ships name "mux".
    const core = makePlugin(root, "mux-core", { manifestName: "mux" })
    expect(codexPluginId(core)).toBe("mux")

    // Marketplace entry name = manifest name (codex enforces equality).
    const market = buildCodexMarketplace([core], root)
    expect(market.plugins[0]!.name).toBe("mux")

    // Per-spawn -c flag uses the manifest name too.
    const { args } = new CodexPluginAdapter().spawnArgs([core], { name: "s" })
    expect(args).toEqual(["-c", `plugins."mux@${CODEX_MARKETPLACE_NAME}".enabled=true`])
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})

test("codexPluginId falls back to the registry name when the manifest is absent", () => {
  const root = tmpRoot()
  try {
    expect(codexPluginId(makePlugin(root, "x", { codexManifest: false }))).toBe("x")
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})

test("spawnArgs emits one -c plugins.\"<name>@mux\".enabled=true per compatible codex plugin", () => {
  const root = tmpRoot()
  try {
    const sp = makePlugin(root, "superpowers")
    const core = makePlugin(root, "mux-core")
    const { args, env } = new CodexPluginAdapter().spawnArgs([sp, core], { name: "sess" })
    expect(args).toEqual([
      "-c", `plugins."superpowers@${CODEX_MARKETPLACE_NAME}".enabled=true`,
      "-c", `plugins."mux-core@${CODEX_MARKETPLACE_NAME}".enabled=true`,
    ])
    expect(env).toEqual({})
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})

test("spawnArgs skips disabled, non-codex-scoped, incompatible, and per-session-disabled plugins", () => {
  const root = tmpRoot()
  try {
    const a = new CodexPluginAdapter()
    expect(a.spawnArgs([makePlugin(root, "d", { enabled: false })], { name: "s" }).args).toEqual([])
    expect(a.spawnArgs([makePlugin(root, "x", { scopes: ["claude", "cursor"] })], { name: "s" }).args).toEqual([])
    expect(a.spawnArgs([makePlugin(root, "n", { codexManifest: false })], { name: "s" }).args).toEqual([])
    const ov = makePlugin(root, "o", { overrides: { sess: { enabled: false } } })
    expect(a.spawnArgs([ov], { name: "sess" }).args).toEqual([])
    expect(a.spawnArgs([ov], { name: "other" }).args).toEqual(["-c", `plugins."o@${CODEX_MARKETPLACE_NAME}".enabled=true`])
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})

test("prepareGlobal writes marketplace.json and installs each codex plugin once", async () => {
  const root = tmpRoot()
  try {
    const marketplacePath = join(root, ".agents", "plugins", "marketplace.json")
    const installed: string[] = []
    const a = new CodexPluginAdapter({ marketplacePath, installPlugin: (name) => installed.push(name) })
    const sp = makePlugin(root, "superpowers")
    const core = makePlugin(root, "mux-core")
    const skipped = makePlugin(root, "claude-only", { scopes: ["claude"] })
    await a.prepareGlobal([sp, core, skipped])

    expect(existsSync(marketplacePath)).toBe(true)
    const written = JSON.parse(readFileSync(marketplacePath, "utf8"))
    expect(written.name).toBe(CODEX_MARKETPLACE_NAME)
    expect(written.plugins.map((p: any) => p.name)).toEqual(["superpowers", "mux-core"])
    expect(installed).toEqual(["superpowers", "mux-core"])
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})

test("prepareGlobal is resilient: an installer that throws does not abort the rest", async () => {
  const root = tmpRoot()
  try {
    const marketplacePath = join(root, ".agents", "plugins", "marketplace.json")
    const installed: string[] = []
    const a = new CodexPluginAdapter({
      marketplacePath,
      installPlugin: (name) => { if (name === "superpowers") throw new Error("already added"); installed.push(name) },
    })
    await a.prepareGlobal([makePlugin(root, "superpowers"), makePlugin(root, "mux-core")])
    // superpowers threw (swallowed); mux-core still installed; file still written
    expect(installed).toEqual(["mux-core"])
    expect(existsSync(marketplacePath)).toBe(true)
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})

test("default Codex plugin commands resolve Windows shims and preserve POSIX argv", async () => {
  const syncCalls: any[] = []
  runCodexPluginCommandSync(["plugin", "add", "mux@mux"], {
    platform: "win32", env: { Path: "C:\\Tools", ComSpec: "C:\\Windows\\cmd.exe" },
    fileExists: (p) => p.toLowerCase() === "c:\\tools\\codex.cmd",
    spawnSync: ((command: string, args: string[], options: any) => { syncCalls.push({ command, args, options }); return { status: 0 } }) as any,
  })
  expect(syncCalls[0].command).toBe("C:\\Windows\\cmd.exe")
  expect(syncCalls[0].options.windowsVerbatimArguments).toBe(true)

  const asyncCalls: any[] = []
  await runCodexPluginCommand(["plugin", "add", "mux@mux"], { CODEX_HOME: "C:\\State" }, {
    platform: "win32", env: { Path: "C:\\PS" },
    fileExists: (p) => ["c:\\ps\\codex.ps1", "c:\\ps\\pwsh.exe"].includes(p.toLowerCase()),
    spawn: ((command: string, args: string[], options: any) => {
      asyncCalls.push({ command, args, options })
      const child = new EventEmitter() as any
      queueMicrotask(() => child.emit("exit", 0, null))
      return child
    }) as any,
  })
  expect(asyncCalls[0].command).toBe("C:\\PS\\pwsh.exe")
  expect(asyncCalls[0].args).toContain("-File")
  expect(asyncCalls[0].options.env.CODEX_HOME).toBe("C:\\State")
})

test("synchronous Codex plugin commands report signal termination clearly", () => {
  expect(() => runCodexPluginCommandSync(["plugin", "add", "mux@mux"], {
    platform: "linux", env: { PATH: "/bin" }, fileExists: () => true,
    spawnSync: (() => ({ status: null, signal: "SIGTERM", error: undefined })) as any,
  })).toThrow(/signal=SIGTERM/)
})
