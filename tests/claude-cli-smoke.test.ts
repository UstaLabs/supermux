import { expect, test } from "bun:test"
import { readFileSync } from "node:fs"
import { resolve } from "node:path"

// #126: the Docker image's build-time Claude check must cover every flag the broker can pass, or a new
// flag could ship that the pinned CLI rejects (as --await-initialize did against claude-code 2.1.162).
const root = resolve(import.meta.dir, "..")
const source = readFileSync(resolve(root, "packages/supermux-core/src/claude/index.ts"), "utf8")
const argvBody = source.slice(source.indexOf("function argv("), source.indexOf("\n}\n", source.indexOf("function argv(")))
const smoke = readFileSync(resolve(root, "scripts/claude-cli-smoke.sh"), "utf8")
const dockerfile = readFileSync(resolve(root, "Dockerfile"), "utf8")

test("the smoke script passes every flag argv() can launch Claude with", () => {
  const flags = [...new Set([...argvBody.matchAll(/[`']--([a-zA-Z][a-zA-Z-]*)/g)].map((m) => `--${m[1]}`))]
  expect(flags.length).toBeGreaterThan(10)
  expect(flags.filter((f) => !smoke.includes(f))).toEqual([])
})

test("the Docker image runs the smoke script against its bundled Claude", () => {
  expect(dockerfile).toContain("scripts/claude-cli-smoke.sh")
  expect(dockerfile).not.toContain("claude-code@2.1.162")
})
