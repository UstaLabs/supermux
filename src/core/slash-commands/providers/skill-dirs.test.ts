import { test, expect } from "bun:test"
import { mkdtempSync, mkdirSync, writeFileSync, rmSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { scanSkillDirs } from "./skill-dirs"

test("scanSkillDirs reads frontmatter names, falls back to the folder name, and dedupes across dirs", () => {
  const root = mkdtempSync(join(tmpdir(), "skills-scan-"))
  try {
    const a = join(root, "a")
    const b = join(root, "b")
    mkdirSync(join(a, "soul"), { recursive: true })
    writeFileSync(join(a, "soul", "SKILL.md"), "---\nname: soul\ndescription: d\n---\nbody\n")
    mkdirSync(join(a, "no-skill-md"), { recursive: true })
    mkdirSync(join(b, "dirname-fallback"), { recursive: true })
    writeFileSync(join(b, "dirname-fallback", "SKILL.md"), "no frontmatter\n")
    mkdirSync(join(b, "soul-dupe"), { recursive: true })
    writeFileSync(join(b, "soul-dupe", "SKILL.md"), "---\nname: soul\n---\n")
    const cmds = scanSkillDirs([a, b, join(root, "missing")])
    expect(cmds.map((c) => c.name).sort()).toEqual(["dirname-fallback", "soul"])
    expect(cmds[0]!.sigil).toBe("/")
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})

test("scanSkillDirs reads the description, one line or a folded block", () => {
  const root = mkdtempSync(join(tmpdir(), "skills-desc-"))
  try {
    mkdirSync(join(root, "one"), { recursive: true })
    writeFileSync(join(root, "one", "SKILL.md"), "---\nname: one\ndescription: A one-line description\n---\nbody\n")
    mkdirSync(join(root, "folded"), { recursive: true })
    writeFileSync(join(root, "folded", "SKILL.md"), "---\nname: folded\ndescription: >-\n  First part,\n  second part.\nother: x\n---\nbody\n")
    mkdirSync(join(root, "quoted"), { recursive: true })
    writeFileSync(join(root, "quoted", "SKILL.md"), "---\nname: quoted\ndescription: \"Quoted text\"\n---\n")
    const byName = Object.fromEntries(scanSkillDirs([root]).map((c) => [c.name, c.description]))
    expect(byName).toEqual({ one: "A one-line description", folded: "First part, second part.", quoted: "Quoted text" })
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})

test("scanSkillDirs leaves out a skill marked disable-slash-command: true", () => {
  const root = mkdtempSync(join(tmpdir(), "skills-hidden-"))
  try {
    mkdirSync(join(root, "hidden"), { recursive: true })
    writeFileSync(join(root, "hidden", "SKILL.md"), "---\nname: hidden\ndisable-slash-command: true\n---\n")
    mkdirSync(join(root, "shown"), { recursive: true })
    writeFileSync(join(root, "shown", "SKILL.md"), "---\nname: shown\ndisable-slash-command: false\n---\n")
    expect(scanSkillDirs([root]).map((c) => c.name)).toEqual(["shown"])
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})
