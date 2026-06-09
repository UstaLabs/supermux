import { test, expect, beforeEach, afterEach } from "bun:test"
import { mkdtempSync, rmSync, existsSync, readFileSync, writeFileSync, mkdirSync } from "fs"
import { join } from "path"
import { tmpdir } from "os"
import { initMux, seedSoulName } from "../src/core/memory/init"
import { buildMemoryInjection } from "../src/core/memory/injector"
import { rebuildIndex } from "../src/core/memory/rebuild"
import { buildDomainIndex, buildAgentsMd } from "../src/core/memory/index-builder"

let tmp: string

beforeEach(() => {
  tmp = mkdtempSync(join(tmpdir(), "agentmux-test-"))
})

afterEach(() => {
  rmSync(tmp, { recursive: true, force: true })
})

test("initMux creates directory structure", () => {
  const root = initMux(tmp)
  expect(root).toBe(tmp)
  expect(existsSync(join(tmp, "agents.md"))).toBe(true)
  expect(existsSync(join(tmp, "soul.md"))).toBe(true)
  expect(existsSync(join(tmp, "conventions.md"))).toBe(true)
  expect(existsSync(join(tmp, "personal", "identity.md"))).toBe(true)
  expect(existsSync(join(tmp, "personal", "preferences.md"))).toBe(true)
  expect(existsSync(join(tmp, "domains", "_inbox.md"))).toBe(true)
  expect(existsSync(join(tmp, "skills"))).toBe(true)
})

test("initMux is idempotent — does not overwrite existing files", () => {
  initMux(tmp)
  writeFileSync(join(tmp, "soul.md"), "# Custom Soul")
  initMux(tmp)
  expect(readFileSync(join(tmp, "soul.md"), "utf8")).toBe("# Custom Soul")
})

test("initMux does NOT clobber a custom file written before it ran (the onboarding soul bug)", () => {
  // Repro: the onboarding soul editor (setSoul) writes a custom soul.md into a
  // mux home that isn't initialized yet (no agents.md). initMux later (at PA
  // spawn) must seed only the MISSING files, never overwrite the custom soul.
  writeFileSync(join(tmp, "soul.md"), "# waifuu\nYou are a custom persona. MARKER42.")
  expect(existsSync(join(tmp, "agents.md"))).toBe(false)
  initMux(tmp)
  expect(readFileSync(join(tmp, "soul.md"), "utf8")).toContain("MARKER42")
  expect(existsSync(join(tmp, "agents.md"))).toBe(true)
})

test("seedSoulName personalizes a pristine default soul.md with the PA name", () => {
  initMux(tmp)
  seedSoulName("chewy", tmp)
  const soul = readFileSync(join(tmp, "soul.md"), "utf8")
  expect(soul).toContain("# chewy")
  expect(soul).toContain("You are chewy, the user's personal AI assistant")
  // keeps the rest of the template
  expect(soul).toContain("## Communication Style")
})

test("seedSoulName never clobbers a customized soul.md", () => {
  initMux(tmp)
  writeFileSync(join(tmp, "soul.md"), "# My Own Soul\nYou are whatever I say.")
  seedSoulName("chewy", tmp)
  expect(readFileSync(join(tmp, "soul.md"), "utf8")).toBe("# My Own Soul\nYou are whatever I say.")
})

test("seedSoulName is idempotent (second call is a no-op once seeded)", () => {
  initMux(tmp)
  seedSoulName("chewy", tmp)
  const after1 = readFileSync(join(tmp, "soul.md"), "utf8")
  seedSoulName("chewy", tmp) // seeded soul no longer matches the template → untouched
  expect(readFileSync(join(tmp, "soul.md"), "utf8")).toBe(after1)
})

test("agents.md contains domain index placeholder when no domains", () => {
  initMux(tmp)
  const content = readFileSync(join(tmp, "agents.md"), "utf8")
  expect(content).toContain("(no domains yet)")
})

test("buildDomainIndex reads description from frontmatter", () => {
  const domainsDir = join(tmp, "domains")
  mkdirSync(domainsDir, { recursive: true })
  writeFileSync(
    join(domainsDir, "ios-webkit.md"),
    "---\ndescription: iOS Safari PWA constraints and push gotchas\ntags: [ios, pwa]\n---\n\n# iOS WebKit\n"
  )
  const index = buildDomainIndex(domainsDir)
  expect(index).toContain("ios-webkit: iOS Safari PWA constraints and push gotchas")
})

test("buildDomainIndex skips _inbox.md", () => {
  const domainsDir = join(tmp, "domains")
  mkdirSync(domainsDir, { recursive: true })
  writeFileSync(join(domainsDir, "_inbox.md"), "---\ndescription: inbox\n---\n")
  writeFileSync(join(domainsDir, "flutter.md"), "---\ndescription: Flutter dev\n---\n")
  const index = buildDomainIndex(domainsDir)
  expect(index).not.toContain("_inbox")
  expect(index).toContain("flutter: Flutter dev")
})

test("buildAgentsMd is slim — static how-to moved to environment.md", () => {
  const domainsDir = join(tmp, "domains")
  mkdirSync(domainsDir, { recursive: true })
  writeFileSync(join(domainsDir, "flutter.md"), "---\ndescription: Flutter dev\n---\n")
  const out = buildAgentsMd(tmp)
  expect(out).toContain("flutter: Flutter dev")
  expect(out).not.toContain("## Writing Back")
  expect(out).not.toContain("## Rules")
  expect(out).not.toContain("## Your Role")
})

test("rebuildIndex updates agents.md with new domains", () => {
  initMux(tmp)
  writeFileSync(
    join(tmp, "domains", "agentmux.md"),
    "---\ndescription: Broker architecture and multi-agent protocol\ntags: [agentmux]\n---\n"
  )
  rebuildIndex(tmp)
  const content = readFileSync(join(tmp, "agents.md"), "utf8")
  expect(content).toContain("mux: Broker architecture and multi-agent protocol")
})

test("buildMemoryInjection for main agent", () => {
  process.env.MUX_HOME = tmp
  initMux(tmp)
  const injection = buildMemoryInjection({ role: "main" })
  expect(injection).toContain("You are the main agent")
  expect(injection).toContain(tmp)
  delete process.env.MUX_HOME
})

test("buildMemoryInjection for worker agent with task", () => {
  process.env.MUX_HOME = tmp
  initMux(tmp)
  const injection = buildMemoryInjection({
    role: "worker",
    taskDescription: "Fix the push notification bug",
  })
  expect(injection).toContain("You are a worker agent")
  expect(injection).toContain("Fix the push notification bug")
  delete process.env.MUX_HOME
})

test("multiple domains sorted alphabetically in index", () => {
  const domainsDir = join(tmp, "domains")
  mkdirSync(domainsDir, { recursive: true })
  writeFileSync(join(domainsDir, "myproject.md"), "---\ndescription: My project stuff\n---\n")
  writeFileSync(join(domainsDir, "android.md"), "---\ndescription: Android dev\n---\n")
  writeFileSync(join(domainsDir, "flutter.md"), "---\ndescription: Flutter dev\n---\n")
  const index = buildDomainIndex(domainsDir)
  const lines = index.split("\n")
  expect(lines[0]).toContain("android")
  expect(lines[1]).toContain("flutter")
  expect(lines[2]).toContain("myproject")
})

test("domain without frontmatter falls back to heading", () => {
  const domainsDir = join(tmp, "domains")
  mkdirSync(domainsDir, { recursive: true })
  writeFileSync(join(domainsDir, "random.md"), "# Random Notes\n\nSome content here.\n")
  const index = buildDomainIndex(domainsDir)
  expect(index).toContain("random: Random Notes")
})
