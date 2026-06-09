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

export function renderMarkdown(text: string): string {
  const html = marked.parse(text, { async: false }) as string
  const sanitized = DOMPurify.sanitize(html, {
    ADD_ATTR: ["target", "data-path", "data-line", "data-line-end"],
  })
  // Markdown [label](path:line) links are file-links from the marked renderer above.
  return linkifyFilePaths(sanitized)
}
