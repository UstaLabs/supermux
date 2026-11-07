import { test, expect } from "bun:test"
import { mkdtempSync, mkdirSync, writeFileSync, rmSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { loadPluginsRegistry, migrateOverridesToSessionIds, sessionPlugins } from "./index"

function setup(): { root: string; file: string } {
  const root = mkdtempSync(join(tmpdir(), "plugins-overrides-"))
  return { root, file: join(root, "plugins.json") }
}

function writeSkillPlugin(root: string, name: string): string {
  const dir = join(root, name)
  mkdirSync(join(dir, "skills", "demo"), { recursive: true })
  writeFileSync(join(dir, "skills", "demo", "SKILL.md"), "---\nname: demo\n---\n")
  return dir
}

test("name-keyed session overrides are re-keyed to those sessions' ids, once (#102)", () => {
  const { root, file } = setup()
  try {
    const dir = writeSkillPlugin(root, "sp")
    writeFileSync(file, JSON.stringify({
      version: 1,
      plugins: [{
        // grok: skill trees alone make the plugin compatible.
        name: "sp", source: { type: "local", path: dir }, enabled: true, scopes: ["grok"],
        perSessionOverrides: { "Old Name": { enabled: false }, "id-2": { enabled: false }, "Nobody": { enabled: false } },
      }],
    }))
    const sessions = [{ id: "id-1", name: "Old Name" }, { id: "id-2", name: "Two" }, { id: "id-3", name: "Old Name" }]

    expect(migrateOverridesToSessionIds(sessions, { file, pluginsDir: root })).toBe(true)
    expect(loadPluginsRegistry({ file, pluginsDir: root }).plugins[0]!.perSessionOverrides).toEqual({
      "id-1": { enabled: false }, "id-3": { enabled: false }, "id-2": { enabled: false }, "Nobody": { enabled: false },
    })
    expect(sessionPlugins("grok", "id-1", { file, pluginsDir: root })).toEqual([])
    expect(sessionPlugins("grok", "id-4", { file, pluginsDir: root })).toEqual([dir])

    // Once: a later session named like an old key is not switched off by a second run.
    expect(migrateOverridesToSessionIds([...sessions, { id: "id-5", name: "Nobody" }], { file, pluginsDir: root })).toBe(false)
    expect(sessionPlugins("grok", "id-5", { file, pluginsDir: root })).toEqual([dir])
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})
