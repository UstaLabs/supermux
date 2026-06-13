import { describe, expect, it } from "bun:test"
import { marked } from "marked"
import { injectCodeCopyButtons, isMarkdownPath, linkifyFilePaths, linkifyMarkdownFileAnchors, wrapTables } from "./markdown"

describe("isMarkdownPath", () => {
  it("matches markdown extensions case-insensitively", () => {
    for (const p of ["README.md", "/docs/Guide.MARKDOWN", "notes.mdown", "a.mkd", "x.mdx"]) {
      expect(isMarkdownPath(p)).toBe(true)
    }
  })
  it("rejects non-markdown files", () => {
    for (const p of ["main.ts", "style.css", "mdfile", "readme.md.bak", "a.md.ts"]) {
      expect(isMarkdownPath(p)).toBe(false)
    }
  })
})

function fileLink(
  display: string,
  path: string,
  opts?: { line?: number; endLine?: number },
): string {
  const escaped = path.replace(/"/g, "&quot;")
  let attrs = `class="file-link" data-path="${escaped}"`
  if (opts?.line !== undefined) attrs += ` data-line="${opts.line}"`
  if (opts?.endLine !== undefined) attrs += ` data-line-end="${opts.endLine}"`
  return `<a ${attrs}>${display}</a>`
}

describe("linkifyFilePaths", () => {
  it("linkifies relative paths", () => {
    const html = linkifyFilePaths("See src/main.ts for details.")
    expect(html).toContain(fileLink("src/main.ts", "src/main.ts"))
  })

  it("linkifies absolute paths", () => {
    const path = "/home/user/projects/project-api/src/main.ts"
    const html = linkifyFilePaths(`Updated ${path} today.`)
    expect(html).toContain(fileLink(path, path))
  })

  it("linkifies home-relative paths", () => {
    const html = linkifyFilePaths("Check ~/projects/app/src/foo.ts")
    expect(html).toContain(fileLink("~/projects/app/src/foo.ts", "~/projects/app/src/foo.ts"))
  })

  it("does not linkify unknown extensions", () => {
    const html = linkifyFilePaths("Binary at src/assets/logo.png")
    expect(html).not.toContain('class="file-link"')
  })

  it("linkifies paths with single-line suffix", () => {
    const html = linkifyFilePaths("See src/main.ts:105 for details.")
    expect(html).toContain(fileLink("src/main.ts:105", "src/main.ts", { line: 105 }))
  })

  it("linkifies paths with line range suffix", () => {
    const html = linkifyFilePaths("Block at src/utils.ts:10-20")
    expect(html).toContain(
      fileLink("src/utils.ts:10-20", "src/utils.ts", { line: 10, endLine: 20 }),
    )
  })

  it("preserves display text including line suffix", () => {
    const html = linkifyFilePaths("Jump to src/main.ts:105")
    expect(html).toContain(">src/main.ts:105</a>")
  })

  it("does not linkify non-numeric line suffix", () => {
    const html = linkifyFilePaths("Broken file.ts:abc reference")
    expect(html).not.toContain('class="file-link"')
    expect(html).toContain("file.ts:abc")
  })

  it("does not linkify inverted line ranges", () => {
    const html = linkifyFilePaths("Bad range file.ts:20-10")
    expect(html).not.toContain('class="file-link"')
    expect(html).toContain("file.ts:20-10")
  })
})

describe("linkifyMarkdownFileAnchors", () => {
  it("converts Codex-style absolute path:line markdown links", () => {
    const path = "/home/user/projects/project-api/src/core/session-manager/session-store.ts"
    const html = linkifyMarkdownFileAnchors(
      `<p>See <a href="${path}:43">session-store.ts</a>.</p>`,
    )
    expect(html).toContain(fileLink("session-store.ts", path, { line: 43 }))
  })

  it("converts relative path:line markdown links", () => {
    const html = linkifyMarkdownFileAnchors(`<a href="src/main.ts:105">main.ts</a>`)
    expect(html).toContain(fileLink("main.ts", "src/main.ts", { line: 105 }))
  })

  it("converts path:start-end markdown links", () => {
    const html = linkifyMarkdownFileAnchors(`<a href="src/utils.ts:10-20">utils</a>`)
    expect(html).toContain(
      fileLink("utils", "src/utils.ts", { line: 10, endLine: 20 }),
    )
  })

  it("leaves http links unchanged", () => {
    const html = linkifyMarkdownFileAnchors(
      `<a href="https://example.com/foo.ts:1">docs</a>`,
    )
    expect(html).not.toContain('class="file-link"')
    expect(html).toContain('href="https://example.com/foo.ts:1"')
  })

  it("converts bare-path markdown links without line suffix", () => {
    const html = linkifyMarkdownFileAnchors(`<a href="src/main.ts">main.ts</a>`)
    expect(html).toContain(fileLink("main.ts", "src/main.ts"))
    expect(html).not.toContain('href="src/main.ts"')
  })
})

describe("injectCodeCopyButtons", () => {
  it("wraps a fenced code block with a code-block container and copy button", () => {
    const html = '<pre><code class="language-js">const x = 1;\n</code></pre>\n'
    const out = injectCodeCopyButtons(html)
    expect(out).toContain('<div class="code-block">')
    expect(out).toContain('class="code-copy-btn"')
    expect(out).toContain('aria-label="Copy code"')
    // original code block is preserved verbatim inside the wrapper
    expect(out).toContain('<pre><code class="language-js">const x = 1;\n</code></pre>')
  })

  it("places the button before the <pre> so it overlays the top-right", () => {
    const out = injectCodeCopyButtons("<pre><code>hi\n</code></pre>")
    expect(out.indexOf("code-copy-btn")).toBeLessThan(out.indexOf("<pre>"))
  })

  it("leaves inline <code> untouched (block code only)", () => {
    const html = "<p>Run <code>npm test</code> now.</p>"
    expect(injectCodeCopyButtons(html)).toBe(html)
  })

  it("adds a button to every code block", () => {
    const html = "<pre><code>one\n</code></pre>\n<pre><code>two\n</code></pre>\n"
    const out = injectCodeCopyButtons(html)
    expect(out.match(/code-copy-btn/g)?.length).toBe(2)
    expect(out.match(/class="code-block"/g)?.length).toBe(2)
  })

  it("does not match escaped </pre> appearing as code content", () => {
    // marked escapes < and > inside code, so a literal </pre> can't close the block early
    const html = "<pre><code>&lt;/pre&gt; is text\n</code></pre>"
    const out = injectCodeCopyButtons(html)
    expect(out.match(/code-copy-btn/g)?.length).toBe(1)
  })
})

describe("wrapTables", () => {
  it("wraps a table in a scrollable container, preserving it verbatim", () => {
    const html = "<table><thead><tr><th>a</th></tr></thead><tbody><tr><td>1</td></tr></tbody></table>"
    const out = wrapTables(html)
    expect(out).toContain('<div class="md-table-wrap">')
    expect(out).toContain(html)
    expect(out.indexOf('class="md-table-wrap"')).toBeLessThan(out.indexOf("<table>"))
  })

  it("wraps every table", () => {
    const html = "<table><tr><td>1</td></tr></table>\n<table><tr><td>2</td></tr></table>"
    const out = wrapTables(html)
    expect(out.match(/class="md-table-wrap"/g)?.length).toBe(2)
  })

  it("leaves non-table HTML untouched", () => {
    const html = "<p>Just a <code>paragraph</code>.</p>"
    expect(wrapTables(html)).toBe(html)
  })

  it("does not match escaped </table> appearing as cell content", () => {
    // marked escapes < and > inside cells, so a literal </table> can't close the table early
    const html = "<table><tr><td>&lt;/table&gt; is text</td></tr></table>"
    const out = wrapTables(html)
    expect(out.match(/class="md-table-wrap"/g)?.length).toBe(1)
  })
})

describe("marked file-link renderer", () => {
  it("emits file-link without href for Codex-style markdown links", () => {
    const path = "/home/user/projects/project-api/src/core/session-manager/session-store.ts"
    const html = marked.parse(`[session-store.ts](${path}:43)`, { async: false }) as string
    expect(html).toContain('class="file-link"')
    expect(html).toContain(`data-path="${path}"`)
    expect(html).toContain('data-line="43"')
    expect(html).toContain(">session-store.ts</a>")
    expect(html).not.toContain("href=")
  })

  it("does not corrupt existing file-links when linkifying plain paths", () => {
    const path = "/home/user/projects/project-api/src/core/session-manager/socket-server.ts"
    const html = marked.parse(`[socket-server.ts](${path}:12)`, { async: false }) as string
    const out = linkifyFilePaths(html)
    expect(out).toContain(fileLink("socket-server.ts", path, { line: 12 }))
    expect(out).not.toContain('data-path="<a')
    expect(out).not.toContain(`${path}:12">`)
  })
})
