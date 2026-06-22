import { test, expect } from "bun:test"
import { mkdtempSync, mkdirSync, writeFileSync } from "fs"
import { tmpdir } from "os"; import { join } from "path"
import { loadFinishConfig } from "./finish-config"

test("defaults when no config file", () => {
  const dir = mkdtempSync(join(tmpdir(), "mux-cfg-"))
  expect(loadFinishConfig(dir)).toEqual({ defaultAction: "auto", archiveOnMerge: true, prRequiresGreen: false })
})
test("reads .mux/finish.json overrides", () => {
  const dir = mkdtempSync(join(tmpdir(), "mux-cfg-")); mkdirSync(join(dir, ".mux"))
  writeFileSync(join(dir, ".mux", "finish.json"), JSON.stringify({ defaultAction: "pr", archiveOnMerge: false }))
  expect(loadFinishConfig(dir)).toEqual({ defaultAction: "pr", archiveOnMerge: false, prRequiresGreen: false })
})
test("ignores malformed json → defaults", () => {
  const dir = mkdtempSync(join(tmpdir(), "mux-cfg-")); mkdirSync(join(dir, ".mux"))
  writeFileSync(join(dir, ".mux", "finish.json"), "{not json")
  expect(loadFinishConfig(dir).defaultAction).toBe("auto")
})
test("ignores unknown keys and invalid values", () => {
  const dir = mkdtempSync(join(tmpdir(), "mux-cfg-")); mkdirSync(join(dir, ".mux"))
  writeFileSync(join(dir, ".mux", "finish.json"), JSON.stringify({ defaultAction: "bogus", nope: 1 }))
  expect(loadFinishConfig(dir).defaultAction).toBe("auto")  // invalid → falls back
})
