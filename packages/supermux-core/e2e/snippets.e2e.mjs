// The code a reader copies runs: every ```ts run block of the README and of the agent skill, run with Node against
// the stub agent CLIs (support/snippets.mjs says how a block passes). Run `bun run build` first: the blocks import the
// built package.
import { test } from "node:test"
import assert from "node:assert/strict"
import { existsSync, readFileSync } from "node:fs"
import { runBlock, runnableBlocks } from "./support/snippets.mjs"

// The stubs are sh scripts.
const skip = process.platform === "win32" && "the stub agent CLIs are sh scripts"
const DOCS = ["README.md", "skills/supermux-core/SKILL.md"]

const docs = DOCS.map(path => {
  const url = new URL(`../${path}`, import.meta.url)
  const exists = existsSync(url)
  return { path, exists, blocks: exists ? runnableBlocks(readFileSync(url, "utf8")) : [] }
})

for (const { path, exists, blocks } of docs) {
  if (!exists) test(`${path} has its blocks run`, { skip: `${path} is not written yet` }, () => {})
  for (const block of blocks) {
    // The runner's own 60 s timeout comes first, and says which block it was.
    test(`${path} block ${block.number} (line ${block.line}) runs`, { skip, timeout: 120_000 }, () => runBlock(path, block))
  }
}

test("the docs have code that runs: at least one ```ts run block, once both files exist", { skip: docs.some(doc => !doc.exists) && "not every file is written yet" }, () => {
  assert.ok(docs.some(doc => doc.blocks.length), `no \`\`\`ts run block in ${DOCS.join(" or ")}`)
})
