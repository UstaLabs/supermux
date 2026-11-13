// What's new (#90): a release's notes from its edition, the page the apps bundle, so the three never disagree.
//   bun scripts/whatsnew-notes.ts <version>                  GitHub's "What's new" bullets (every platform, tagged)
//   bun scripts/whatsnew-notes.ts <version> --store iphone   a store's text (only what that platform has)
// Prints nothing and exits 0 when the release has no edition, so a caller can fall back to its own notes.
import { existsSync, readFileSync } from "node:fs"
import { resolve } from "node:path"

export type Chapter = { id: string; title?: string; on?: string[] }
export type Edition = { version: string; chapters: Chapter[] }

const NAMES: Record<string, string> = { mac: "Mac", win: "Windows", linux: "Linux", web: "web", android: "Android", iphone: "iPhone" }
const DESKTOP = ["mac", "win", "linux"]

export const editionPath = (version: string) => `apps/ui/src/commonMain/composeResources/files/whatsnew/${version}.html`

const MANIFEST = /<script[^>]*\bid="edition"[^>]*>([\s\S]*?)<\/script>/i

export function readEdition(page: string): Edition {
  const body = MANIFEST.exec(page)?.[1]
  if (!body) throw new Error("no edition manifest (<script type=\"application/json\" id=\"edition\">)")
  return JSON.parse(body) as Edition
}

function where(on: string[]): string {
  if (DESKTOP.every(d => on.includes(d)) && on.length === DESKTOP.length) return "desktop"
  return on.map(p => NAMES[p] ?? p).join(", ")
}

/** GitHub's list: the one place that names everything, so a platform-only chapter says where it is. */
export function githubHighlights(edition: Edition): string[] {
  return edition.chapters.map(c => `- ${c.title ?? c.id}${c.on ? ` (${where(c.on)})` : ""}`)
}

/** A store's "What's new": only the chapters [platform] has, so it never promises what its users can't do. */
export function storeText(edition: Edition, platform: string): string {
  return edition.chapters.filter(c => !c.on || c.on.includes(platform)).map(c => `• ${c.title ?? c.id}`).join("\n")
}

if (import.meta.main) {
  const [version, flag, platform] = process.argv.slice(2)
  if (!version) {
    console.error("usage: bun scripts/whatsnew-notes.ts <version> [--store <platform>]")
    process.exit(2)
  }
  const file = resolve(import.meta.dir, "..", editionPath(version.replace(/^v/, "")))
  if (!existsSync(file)) process.exit(0)
  const edition = readEdition(readFileSync(file, "utf8"))
  console.log(flag === "--store" ? storeText(edition, platform ?? "") : githubHighlights(edition).join("\n"))
}
