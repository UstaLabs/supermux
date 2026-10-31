import { expect, test } from "bun:test"
import { readFileSync } from "node:fs"
import { join } from "node:path"

const root = join(import.meta.dir, "..")
const dockerfile = readFileSync(join(root, "Dockerfile"), "utf8")
const workspaces: string[] = JSON.parse(readFileSync(join(root, "package.json"), "utf8")).workspaces ?? []

// The image runs `bun install` before `COPY . .`, so every workspace's manifest must be copied
// first or Bun stops with "Workspace not found" and the release's docker job fails (v0.17.0).
test("every package.json workspace has its manifest copied before the image's bun install", () => {
  const install = dockerfile.indexOf("RUN bun install")
  expect(install).toBeGreaterThan(-1)
  const beforeInstall = dockerfile.slice(0, install)
  expect(workspaces.length).toBeGreaterThan(0)
  for (const ws of workspaces) expect(beforeInstall).toContain(`COPY ${ws}/package.json ./${ws}/`)
})
