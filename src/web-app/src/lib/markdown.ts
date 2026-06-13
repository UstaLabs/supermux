import { marked } from "marked"
import DOMPurify from "dompurify"
import { FILE_PATH_MATCH_RE, parseFilePathRef, type FilePathRef } from "./file-path-ref"

marked.setOptions({
  gfm: true,
  breaks: true,
})

// Emit file-link HTML at parse time so DOMPurify never leaves a navigable href.
marked.use({
  renderer: {
    link({ href, title, tokens }) {
      const text = this.parser.parseInline(tokens)
      const ref = href ? hrefToFilePathRef(href) : null
      if (ref) return fileLinkHtml(ref, text)

      if (!href) return text
      const safeHref = href.replace(/"/g, "&quot;")
      let out = `<a href="${safeHref}"`
      if (title) out += ` title="${title.replace(/"/g, "&quot;")}"`
      out += `>${text}</a>`
      return out
    },
  },
})

const FILE_EXTENSIONS = new Set([
  "ts", "tsx", "js", "jsx", "vue", "py", "json", "md", "css", "html",
  "yml", "yaml", "toml", "sql", "sh", "bash", "zsh", "go", "rs",
  "rb", "java", "kt", "swift", "c", "cpp", "h", "hpp", "txt",
  "env", "gitignore", "dockerfile", "xml", "svg", "lock",
])

function hasKnownExtension(path: string): boolean {
  const ext = path.split(".").pop()?.toLowerCase() ?? ""
  return FILE_EXTENSIONS.has(ext)
}

function fileLinkHtml(ref: FilePathRef, display: string): string {
  const escapedPath = ref.path.replace(/"/g, "&quot;")
  let attrs = `class="file-link" data-path="${escapedPath}"`
  if (ref.line !== undefined) attrs += ` data-line="${ref.line}"`
  if (ref.endLine !== undefined) attrs += ` data-line-end="${ref.endLine}"`
  return `<a ${attrs}>${display}</a>`
}

function hrefToFilePathRef(href: string): FilePathRef | null {
  if (!href || href.startsWith("#")) return null
  if (/^[a-z][a-z0-9+.-]*:/i.test(href) && !href.startsWith("file://")) return null

  let path = href
  if (href.startsWith("file://")) {
    try {
      path = decodeURIComponent(href.slice("file://".length))
    } catch {
      return null
    }
  }

  const ref = parseFilePathRef(path)
  if (!ref || !hasKnownExtension(ref.path)) return null
  return ref
}

/** Convert markdown `[label](path:line)` anchors into editor file-links. */
export function linkifyMarkdownFileAnchors(html: string): string {
  return html.replace(/<a\s+href="([^"]+)"[^>]*>([\s\S]*?)<\/a>/gi, (full, href, text) => {
    const ref = hrefToFilePathRef(href)
    if (!ref) return full
    return fileLinkHtml(ref, text)
  })
}

/** Linkify bare paths in text nodes only — never inside HTML tags/attributes. */
export function linkifyFilePaths(html: string): string {
  return html.split(/(<[^>]+>)/g).map((part) => {
    if (part.startsWith("<")) return part
    return part.replace(FILE_PATH_MATCH_RE, (match) => {
      const ref = parseFilePathRef(match)
      if (!ref || !hasKnownExtension(ref.path)) return match
      return fileLinkHtml(ref, match)
    })
  }).join("")
}

// Static, data-free markup — safe to inject after sanitization. The copied
// code itself stays inside the already-sanitized <pre><code>, never here.
const COPY_BUTTON =
  '<button class="code-copy-btn" type="button" aria-label="Copy code">' +
  '<svg class="icon-copy" viewBox="0 0 24 24" fill="none" stroke="currentColor"' +
  ' stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">' +
  '<rect width="14" height="14" x="8" y="8" rx="2" ry="2"/>' +
  '<path d="M4 16c-1.1 0-2-.9-2-2V4c0-1.1.9-2 2-2h10c1.1 0 2 .9 2 2"/></svg>' +
  '<svg class="icon-check" viewBox="0 0 24 24" fill="none" stroke="currentColor"' +
  ' stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">' +
  '<path d="M20 6 9 17l-5-5"/></svg></button>'

// Non-greedy, so each <pre>…</pre> matches independently. marked escapes < and >
// inside code, so a literal </pre> in the source can never close a block early.
const CODE_BLOCK_RE = /<pre\b[^>]*>[\s\S]*?<\/pre>/g

/** Wrap each fenced code block in a positioned container carrying a copy button. */
export function injectCodeCopyButtons(html: string): string {
  return html.replace(CODE_BLOCK_RE, (block) => `<div class="code-block">${COPY_BUTTON}${block}</div>`)
}

const MARKDOWN_EXT_RE = /\.(md|markdown|mdown|mkd|mdx)$/i

/** True for paths the editor can render as a markdown preview. */
export function isMarkdownPath(path: string): boolean {
  return MARKDOWN_EXT_RE.test(path)
}

export function renderMarkdown(text: string): string {
  const html = marked.parse(text, { async: false }) as string
  const sanitized = DOMPurify.sanitize(html, {
    ADD_ATTR: ["target", "data-path", "data-line", "data-line-end"],
  })
  // Markdown [label](path:line) links are file-links from the marked renderer above.
  return injectCodeCopyButtons(linkifyFilePaths(sanitized))
}
