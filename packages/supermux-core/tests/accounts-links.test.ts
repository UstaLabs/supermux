// Q5 (#37): a Claude account shares its history with the agent's root by linking folders AND files. Windows lets a
// standard user create folder links (junctions) but not file links without Developer Mode, so a Claude account added
// from the app failed right after sign-in (unsupported_operation). Where this host cannot link files, the account keeps
// its history to itself instead, and says why.
import { afterEach, expect, test } from "bun:test"
import { lstat, mkdtemp, readdir, rm } from "node:fs/promises"
import { tmpdir } from "node:os"
import { join } from "node:path"
import { memoryVault } from "../src/index.js"
import { AccountRegistry } from "../src/accounts/index.js"
import { hostLinks } from "../src/accounts/registry.js"

const dirs: string[] = []
const canLink = hostLinks.canLinkFiles
afterEach(async () => {
  hostLinks.canLinkFiles = canLink
  await Promise.all(dirs.splice(0).map(dir => rm(dir, { recursive: true, force: true })))
})
async function registry() {
  const base = await mkdtemp(join(tmpdir(), "accounts-links-"))
  dirs.push(base)
  return { base, registry: new AccountRegistry(join(base, "state"), ["claude"], { vault: memoryVault(), homes: { claudeRoot: join(base, "root") } }) }
}

test("on a host that cannot link files, a Claude subscription account keeps its history to itself, and says why", async () => {
  hostLinks.canLinkFiles = () => false
  const { base, registry: r } = await registry()
  const account = await r.add({ id: "sub", agent: "claude", method: "subscription" })
  expect(account.isolated).toBe(true)
  expect(account.isolatedBecause).toBe("file_links_unsupported")
  expect(await readdir(join(base, "state", "accounts", "homes", "claude", "sub"))).toEqual([])
  expect((await r.list()).find(a => a.id === "sub")?.isolatedBecause).toBe("file_links_unsupported")
})

test("where it can, the account shares the history as before", async () => {
  hostLinks.canLinkFiles = () => true
  const { base, registry: r } = await registry()
  const account = await r.add({ id: "sub", agent: "claude", method: "subscription" })
  expect(account.isolated).toBeUndefined()
  expect(account.isolatedBecause).toBeUndefined()
  expect((await lstat(join(base, "state", "accounts", "homes", "claude", "sub", "history.jsonl"))).isSymbolicLink()).toBe(true)
})
