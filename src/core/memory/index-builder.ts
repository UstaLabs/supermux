import { readdirSync, readFileSync, existsSync } from "fs"
import { join } from "path"
import { TEMPLATES } from "./templates-embedded"

export function buildAgentsMd(root: string): string {
  const domainsDir = join(root, "domains")
  const domainIndex = buildDomainIndex(domainsDir)

  const template = TEMPLATES["agents.md.tmpl"]!

  return template.replace("{{DOMAIN_INDEX}}", domainIndex)
}

export function buildDomainIndex(domainsDir: string): string {
  if (!existsSync(domainsDir)) return "- (no domains yet)"

  const files = readdirSync(domainsDir).filter(
    (f) => f.endsWith(".md") && f !== "_inbox.md" && !f.endsWith(".digest.md")
  )

  if (files.length === 0) return "- (no domains yet)"

  const lines: string[] = []
  for (const file of files.sort()) {
    const content = readFileSync(join(domainsDir, file), "utf8")
    const desc = extractDescription(content)
    const name = file.replace(/\.md$/, "")
    lines.push(`- ${name}: ${desc}`)
  }
  return lines.join("\n")
}

function extractDescription(content: string): string {
  const match = content.match(/^description:\s*(.+)$/m)
  if (match) return match[1]!.trim()
  const heading = content.match(/^#\s+(.+)$/m)
  if (heading) return heading[1]!.trim()
  return "(no description)"
}
