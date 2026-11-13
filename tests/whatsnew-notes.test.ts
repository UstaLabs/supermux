import { expect, test } from "bun:test"
import { readFileSync } from "node:fs"
import { resolve } from "node:path"
import { editionPath, githubHighlights, readEdition, storeText } from "../scripts/whatsnew-notes"

// What's new (#90): one source. The edition bundled in the app is also the release's "What's new" on GitHub (every
// platform, platform-only items tagged) and each store's text (only what that store's platform has).

const page = `<!doctype html><html><head>
<script type="application/json" id="edition">
{"version":"9.9.9","chapters":[
  {"id":"browser","title":"Open your app right next to the chat","on":["mac","win","android","iphone"]},
  {"id":"cards","title":"Agents can answer with a live page"},
  {"id":"restart","title":"Updates are one click","on":["mac","win","linux"]},
  {"id":"signed","title":"A signed installer for Windows","on":["win"]}
]}
</script></head><body></body></html>`

test("an edition's manifest is read from its page", () => {
  const edition = readEdition(page)
  expect(edition.version).toBe("9.9.9")
  expect(edition.chapters.map(c => c.id)).toEqual(["browser", "cards", "restart", "signed"])
})

test("GitHub gets every chapter, platform-only ones tagged with where they are", () => {
  expect(githubHighlights(readEdition(page))).toEqual([
    "- Open your app right next to the chat (Mac, Windows, Android, iPhone)",
    "- Agents can answer with a live page",
    "- Updates are one click (desktop)",
    "- A signed installer for Windows (Windows)",
  ])
})

test("a store lists only what its platform has, untagged", () => {
  expect(storeText(readEdition(page), "iphone")).toBe("• Open your app right next to the chat\n• Agents can answer with a live page")
  expect(storeText(readEdition(page), "android")).toBe("• Open your app right next to the chat\n• Agents can answer with a live page")
})

test("every chapter of a shipped edition has a title", () => {
  const html = readFileSync(resolve(import.meta.dir, "..", editionPath("0.18.0")), "utf8")
  const edition = readEdition(html)
  expect(edition.version).toBe("0.18.0")
  for (const c of edition.chapters) expect(c.title?.length ?? 0).toBeGreaterThan(0)
})

test("a page without a manifest is refused", () => {
  expect(() => readEdition("<html></html>")).toThrow()
})
