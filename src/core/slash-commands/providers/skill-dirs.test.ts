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
