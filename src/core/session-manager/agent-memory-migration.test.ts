import { afterEach, expect, test } from "bun:test"
import { existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { migrateToAgentMemory } from "./agent-memory-migration"

// Every test runs in its own temp mux home; the user's real ~/.mux is never touched.
const dirs: string[] = []
afterEach(() => { for (const d of dirs.splice(0)) rmSync(d, { recursive: true, force: true }) })

function fixture() {
  const home = mkdtempSync(join(tmpdir(), "agent-mig-")); dirs.push(home)
  writeFileSync(join(home, "soul.md"), "# Dockie\nBe blunt.\n")
  const dockieDir = join(home, "workspace", "Dockie"); mkdirSync(dockieDir, { recursive: true })
  writeFileSync(join(dockieDir, "soul.md"), "Dockie's own soul.")
  const motionDir = join(home, "workspace", "motion designer"); mkdirSync(motionDir, { recursive: true })
  writeFileSync(join(motionDir, "focus.md"), "Motion graphics for the site.")
  writeFileSync(join(motionDir, "soul.md"), "Playful.")
  const agents = [
    { id: "dockie-id", workdir: dockieDir, is_default: true },
    { id: "motion-id", workdir: motionDir, is_default: false },
  ]
  return { home, agents }
}
const history = (home: string, id: string) => readFileSync(join(home, "agent-memory", `${id}.md`), "utf8")

test("the default Agent gets the shared soul.md AND its own, under two headings; others get only their own files", () => {
  const { home, agents } = fixture()
  migrateToAgentMemory({ muxHome: home, agents })
  const dockie = history(home, "dockie-id")
  expect(dockie).toContain("## Identity (migrated from soul.md)\n\n# Dockie\nBe blunt.")
  expect(dockie).toContain("## Identity (migrated from Dockie/soul.md)\n\nDockie's own soul.")
  expect(dockie.indexOf("migrated from soul.md")).toBeLessThan(dockie.indexOf("migrated from Dockie/soul.md"))
  const motion = history(home, "motion-id")
  expect(motion).toContain("## Identity (migrated from focus.md)\n\nMotion graphics for the site.")
  expect(motion).toContain("## Identity (migrated from motion designer/soul.md)\n\nPlayful.")
  expect(motion).not.toContain("Be blunt.")
  expect(motion).not.toContain("## Identity (migrated from soul.md)")
})

test("originals stay on disk, unchanged; running twice adds nothing", () => {
  const { home, agents } = fixture()
  migrateToAgentMemory({ muxHome: home, agents })
  migrateToAgentMemory({ muxHome: home, agents })
  expect(readFileSync(join(home, "soul.md"), "utf8")).toBe("# Dockie\nBe blunt.\n")
  expect(readFileSync(join(agents[1]!.workdir, "focus.md"), "utf8")).toBe("Motion graphics for the site.")
  expect(readFileSync(join(agents[0]!.workdir, "soul.md"), "utf8")).toBe("Dockie's own soul.")
  expect(history(home, "dockie-id").match(/migrated from soul\.md/g)?.length).toBe(1)
  expect(history(home, "dockie-id").match(/migrated from Dockie\/soul\.md/g)?.length).toBe(1)
  expect(history(home, "motion-id").match(/migrated from focus\.md/g)?.length).toBe(1)
})

test("without a default Agent the shared soul goes to the first one", () => {
  const { home, agents } = fixture()
  migrateToAgentMemory({ muxHome: home, agents: agents.map(a => ({ ...a, is_default: false })) })
  expect(history(home, "dockie-id")).toContain("## Identity (migrated from soul.md)")
  expect(history(home, "motion-id")).not.toContain("## Identity (migrated from soul.md)")
})

test("every Agent gets both memory files even with nothing to migrate; zero Agents is a no-op", () => {
  const home = mkdtempSync(join(tmpdir(), "agent-mig-")); dirs.push(home)
  migrateToAgentMemory({ muxHome: home, agents: [] })
  expect(existsSync(join(home, "agent-memory"))).toBe(false)
  migrateToAgentMemory({ muxHome: home, agents: [{ id: "solo", workdir: join(home, "nowhere"), is_default: true }] })
  expect(readFileSync(join(home, "agent-memory", "solo.md"), "utf8")).toBe("")
  expect(readFileSync(join(home, "agent-memory", "solo.digest.md"), "utf8")).toBe("")
})

test("the pristine soul template is not migrated", () => {
  const home = mkdtempSync(join(tmpdir(), "agent-mig-")); dirs.push(home)
  writeFileSync(join(home, "soul.md"), "# Soul\n\nCustomize this file to define your main agent's personality.\n")
  migrateToAgentMemory({ muxHome: home, agents: [{ id: "a", workdir: join(home, "w"), is_default: true }] })
  expect(history(home, "a")).toBe("")
})

const TEMPLATE = `# Soul

Customize this file to define your main agent's personality.

## Identity
- You are the user's personal AI assistant and engineering partner.

## Communication Style
- Concise by default. One sentence when one sentence works.
- No filler ("Great question!", "I'd be happy to help!")
- Match the user's energy — casual when casual, focused when focused.

## Values
- Correctness over speed, but speed matters too
- Action over discussion — once direction is clear, execute
- Honesty — say "I don't know" or "this is a bad idea" when true

## Boundaries
- Ask before destructive operations
- Never share personal/ content with worker agents
`

test("the full template, and the template only seeded with the PA's name, carry nothing the user wrote", () => {
  const seeded = TEMPLATE.replace("# Soul", "# chewy")
    .replace("define your main agent's personality.", "define chewy's personality.")
    .replace("- You are the user's personal AI assistant", "- You are chewy, the user's personal AI assistant")
  for (const text of [TEMPLATE, seeded]) {
    const home = mkdtempSync(join(tmpdir(), "agent-mig-")); dirs.push(home)
    writeFileSync(join(home, "soul.md"), text)
    migrateToAgentMemory({ muxHome: home, agents: [{ id: "a", workdir: join(home, "w"), is_default: true }] })
    expect(history(home, "a")).toBe("")
  }
})

test("a template the user added to is migrated whole", () => {
  const home = mkdtempSync(join(tmpdir(), "agent-mig-")); dirs.push(home)
  writeFileSync(join(home, "soul.md"), TEMPLATE + "- Never push without asking\n")
  migrateToAgentMemory({ muxHome: home, agents: [{ id: "a", workdir: join(home, "w"), is_default: true }] })
  expect(history(home, "a")).toContain("## Identity (migrated from soul.md)\n\n# Soul\n")
  expect(history(home, "a")).toContain("- Never push without asking")
})
