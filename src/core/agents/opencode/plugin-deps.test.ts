import { afterEach, expect, test } from "bun:test"
import { chmodSync, existsSync, lstatSync, mkdirSync, mkdtempSync, readdirSync, readFileSync, readlinkSync, rmSync, utimesSync, writeFileSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { brokerPluginDeps, isCompleteInstall, OpenCodeVersions, seedPluginDeps } from "./plugin-deps"

const dirs: string[] = []
afterEach(() => {
  for (const d of dirs.splice(0)) rmSync(d, { recursive: true, force: true })
})

function tmp(prefix: string): string {
  const d = mkdtempSync(join(tmpdir(), prefix))
  dirs.push(d)
  return d
}

/** What OpenCode leaves in a config folder once its own install of @opencode-ai/plugin [version] has finished. */
function fakeInstall(dir: string, version: string, opts: { finished?: boolean } = {}): string {
  mkdirSync(join(dir, "node_modules", "@opencode-ai", "plugin"), { recursive: true })
  writeFileSync(join(dir, "package.json"), JSON.stringify({ dependencies: { "@opencode-ai/plugin": version } }))
  writeFileSync(join(dir, "node_modules", "@opencode-ai", "plugin", "package.json"), JSON.stringify({ name: "@opencode-ai/plugin", version }))
  if (opts.finished !== false) {
    writeFileSync(join(dir, "package-lock.json"), JSON.stringify({ name: "opencode", lockfileVersion: 3 }))
    writeFileSync(join(dir, "node_modules", ".package-lock.json"), "{}")
  }
  return dir
}

function configDir(): string {
  const dir = join(tmp("oc-session-"), "config", "opencode")
  mkdirSync(dir, { recursive: true })
  writeFileSync(join(dir, "opencode.json"), "{}")
  return dir
}

test("isCompleteInstall: a finished install of that version only", () => {
  const root = tmp("oc-install-")
  expect(isCompleteInstall(fakeInstall(join(root, "a"), "1.18.34"), "1.18.34")).toBe(true)
  expect(isCompleteInstall(join(root, "a"), "1.19.0")).toBe(false)
  expect(isCompleteInstall(fakeInstall(join(root, "b"), "1.18.34", { finished: false }), "1.18.34")).toBe(false)
  expect(isCompleteInstall(join(root, "missing"), "1.18.34")).toBe(false)
})

test("a new session's config folder gets the shared install: manifests copied, node_modules linked", async () => {
  const store = tmp("oc-store-")
  fakeInstall(join(store, "1.18.34"), "1.18.34")
  const dir = configDir()

  expect(await seedPluginDeps({ configDir: dir, version: "1.18.34", store, candidates: () => [] })).toBe("seeded")

  expect(lstatSync(join(dir, "node_modules")).isSymbolicLink()).toBe(true)
  expect(readlinkSync(join(dir, "node_modules"))).toBe(join(store, "1.18.34", "node_modules"))
  expect(readFileSync(join(dir, "package.json"), "utf8")).toBe(readFileSync(join(store, "1.18.34", "package.json"), "utf8"))
  expect(existsSync(join(dir, "package-lock.json"))).toBe(true)
  expect(isCompleteInstall(dir, "1.18.34")).toBe(true)
})

test("a config folder that already has node_modules is left alone", async () => {
  const store = tmp("oc-store-")
  fakeInstall(join(store, "1.18.34"), "1.18.34")
  const dir = fakeInstall(configDir(), "1.17.0")

  expect(await seedPluginDeps({ configDir: dir, version: "1.18.34", store, candidates: () => [] })).toBe("present")

  expect(lstatSync(join(dir, "node_modules")).isSymbolicLink()).toBe(false)
  expect(JSON.parse(readFileSync(join(dir, "package.json"), "utf8")).dependencies["@opencode-ai/plugin"]).toBe("1.17.0")
})

test("with no shared install of the version, another session's finished install is copied into the store first", async () => {
  const store = tmp("oc-store-")
  const other = fakeInstall(configDir(), "1.18.34")
  const dir = configDir()

  expect(await seedPluginDeps({ configDir: dir, version: "1.18.34", store, candidates: () => [other] })).toBe("seeded")

  expect(isCompleteInstall(join(store, "1.18.34"), "1.18.34")).toBe(true)
  expect(readlinkSync(join(dir, "node_modules"))).toBe(join(store, "1.18.34", "node_modules"))
  // The other session keeps its own install.
  expect(lstatSync(join(other, "node_modules")).isSymbolicLink()).toBe(false)
  expect(isCompleteInstall(other, "1.18.34")).toBe(true)
})

test("an unfinished or other-version install is never taken; OpenCode then installs for itself as before", async () => {
  const store = tmp("oc-store-")
  const unfinished = fakeInstall(configDir(), "1.18.34", { finished: false })
  const older = fakeInstall(configDir(), "1.17.0")
  const dir = configDir()

  expect(await seedPluginDeps({ configDir: dir, version: "1.18.34", store, candidates: () => [unfinished, older] })).toBe("unavailable")

  expect(existsSync(join(store, "1.18.34"))).toBe(false)
  expect(existsSync(join(dir, "node_modules"))).toBe(false)
  expect(existsSync(join(dir, "package.json"))).toBe(false)
})

test("an unknown OpenCode version seeds nothing", async () => {
  const store = tmp("oc-store-")
  fakeInstall(join(store, "1.18.34"), "1.18.34")
  const dir = configDir()

  expect(await seedPluginDeps({ configDir: dir, version: undefined, store, candidates: () => [] })).toBe("unavailable")

  expect(existsSync(join(dir, "node_modules"))).toBe(false)
})

const windows = process.platform === "win32"

/** A fake `opencode` (a .cmd shim on Windows, a shell script elsewhere) that runs [body] and counts its runs. */
function writeFakeOpenCode(bin: string, count: string, body: string): void {
  writeFileSync(bin, windows ? `@echo off\r\necho x>>"${count}"\r\n${body}\r\n` : `#!/bin/sh\necho x >> "${count}"\n${body}\n`)
  if (!windows) chmodSync(bin, 0o755)
}

/** A fake `opencode` that prints [version] for --version and counts its runs. */
function fakeOpenCode(dir: string, version: string): { bin: string; runs: () => number } {
  const bin = join(dir, windows ? "opencode.cmd" : "opencode")
  const count = join(dir, "runs")
  writeFakeOpenCode(bin, count, `echo ${version}`)
  return { bin, runs: () => (existsSync(count) ? readFileSync(count, "utf8").trim().split("\n").length : 0) }
}

test("OpenCodeVersions asks the binary once per binary, and again when the binary changes", async () => {
  const dir = tmp("oc-bin-")
  const { bin, runs } = fakeOpenCode(dir, "1.18.34")
  const versions = new OpenCodeVersions(() => bin)

  expect(versions.known()).toBeUndefined()
  expect(await versions.probe()).toBe("1.18.34")
  expect(await versions.probe()).toBe("1.18.34")
  expect(versions.known()).toBe("1.18.34")
  expect(runs()).toBe(1)

  // An upgrade replaces the binary.
  writeFakeOpenCode(bin, join(dir, "runs"), `echo 1.19.0`)
  utimesSync(bin, new Date(), new Date(Date.now() + 5_000))
  expect(versions.known()).toBeUndefined()
  expect(await versions.probe()).toBe("1.19.0")
  expect(runs()).toBe(2)
})

test("OpenCodeVersions remembers the version across restarts, for the same binary", async () => {
  const dir = tmp("oc-bin-")
  const { bin, runs } = fakeOpenCode(dir, "1.18.34")
  const cacheFile = join(dir, "versions.json")
  expect(await new OpenCodeVersions(() => bin, cacheFile).probe()).toBe("1.18.34")

  const later = new OpenCodeVersions(() => bin, cacheFile)
  expect(later.known()).toBe("1.18.34")
  expect(await later.probe()).toBe("1.18.34")
  expect(runs()).toBe(1)
})

test("two sessions seeded at once share one copy of the other session's install", async () => {
  const store = tmp("oc-store-")
  const other = fakeInstall(configDir(), "1.18.34")
  const a = configDir()
  const b = configDir()

  const results = await Promise.all([a, b].map((dir) => seedPluginDeps({ configDir: dir, version: "1.18.34", store, candidates: () => [other] })))

  expect(results).toEqual(["seeded", "seeded"])
  expect(readlinkSync(join(a, "node_modules"))).toBe(join(store, "1.18.34", "node_modules"))
  expect(readlinkSync(join(b, "node_modules"))).toBe(join(store, "1.18.34", "node_modules"))
  expect(readdirSync(store)).toEqual(["1.18.34"])
})

test("OpenCodeVersions: no binary, or a failing one, gives no version", async () => {
  expect(await new OpenCodeVersions(() => null).probe()).toBeUndefined()
  const dir = tmp("oc-bin-")
  const bin = join(dir, windows ? "opencode.cmd" : "opencode")
  writeFakeOpenCode(bin, join(dir, "runs"), windows ? "exit /b 3" : "exit 3")
  expect(await new OpenCodeVersions(() => bin).probe()).toBeUndefined()
})

test("the broker's plugin deps: a store in the state folder, every OpenCode session's config folder, a remembered version", async () => {
  const state = tmp("oc-state-")
  for (const name of ["one", "two"]) mkdirSync(join(state, "agents", "opencode", name, "config", "opencode"), { recursive: true })
  mkdirSync(join(state, "agents", "opencode", "no-config"), { recursive: true })
  const { bin } = fakeOpenCode(tmp("oc-bin-"), "1.18.34")

  const deps = brokerPluginDeps(state, () => bin)

  expect(deps.store).toBe(join(state, "opencode-plugin-deps"))
  expect(deps.candidates().sort()).toEqual(["one", "two"].map((name) => join(state, "agents", "opencode", name, "config", "opencode")))
  // The probe started with the deps; once it has answered, the version is known without waiting.
  await new OpenCodeVersions(() => bin, join(deps.store, "versions.json")).probe()
  expect(brokerPluginDeps(state, () => bin).version()).toBe("1.18.34")
})
