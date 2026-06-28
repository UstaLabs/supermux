export interface Section {
  heading: string // "" for preamble / headingless content
  body: string
}

/**
 * Split a markdown document into sections keyed by `#`/`##` headings. YAML
 * frontmatter (a leading `---` … `---` block) is stripped. Used to index each
 * dated `## Title (YYYY-MM-DD)` entry of a domain file as its own search row.
 */
export function splitSections(content: string): Section[] {
  const stripped = content.replace(/^---\n[\s\S]*?\n---\n?/, "")
  const lines = stripped.split("\n")
  const sections: Section[] = []
  let heading = ""
  let body: string[] = []
  const flush = () => {
    const text = body.join("\n").trim()
    if (heading || text) sections.push({ heading, body: text })
    body = []
  }
  for (const line of lines) {
    const m = line.match(/^#{1,6}\s+(.+?)\s*$/)
    if (m) {
      flush()
      heading = m[1]!.trim()
    } else {
      body.push(line)
    }
  }
  flush()
  return sections
}
