import { readFileSync } from "node:fs"
import { dirname, join } from "node:path"
import { fileURLToPath } from "node:url"
import { expect, test } from "bun:test"

const dir = dirname(fileURLToPath(import.meta.url))
const entry = readFileSync(join(dir, "cm6-entry.mjs"), "utf8")

/** Extensions the native editor must highlight (last path segment, lowercased). */
const MAINSTREAM_EXTS = [
  "js", "mjs", "cjs", "jsx", "ts", "tsx", "mts", "cts",
  "py", "pyi",
  "java",
  "c", "h", "cc", "cpp", "hpp", "cxx", "hxx",
  "cs", "csx",
  "m", "mm",
  "rs", "go", "php",
  "swift", "kt", "kts", "dart", "scala", "sc",
  "rb", "groovy", "gradle",
  "lua", "pl", "pm",
  "r", "jl", "hs", "erl",
  "fs", "fsx", "ml",
  "clj", "cljs",
  "elm", "cr", "coffee",
  "sql", "json", "jsonc",
  "md", "markdown", "mdx",
  "html", "htm", "vue",
  "css", "scss", "sass", "less",
  "xml", "svg",
  "yaml", "yml", "toml", "ini", "properties",
  "sh", "bash", "zsh", "ps1", "psm1",
  "proto",
  "tex",
  "diff", "patch",
  "wat", "wast",
  "pug", "jade",
  "f90",
  "pas",
  "vb", "vbs",
  "hx",
  "glsl", "frag", "vert",
  "cmake",
]

test("cm6-entry maps mainstream file extensions", () => {
  for (const ext of MAINSTREAM_EXTS) {
    expect(entry.includes(`case "${ext}"`), `missing case "${ext}"`).toBe(true)
  }
})

test("cm6-entry recognizes Dockerfile and CMakeLists.txt by basename", () => {
  expect(entry).toContain('lower === "dockerfile"')
  expect(entry).toContain('lower === "cmakelists.txt"')
})

test("cm6-entry renders walkthrough comment threads + composer as in-editor widgets", () => {
  // The bridge callbacks the desktop shim (EditorBridgeShims.kt) defines and parses.
  for (const fn of ["onCommentSubmit", "onReplySubmit", "onResolveThread", "onComposerState"]) {
    expect(entry.includes(`bridge().${fn}(`), `missing bridge().${fn}`).toBe(true)
  }
  expect(entry).toContain("class ThreadWidget")
  expect(entry).toContain("class ComposerWidget")
  expect(entry).toContain("normalizedThreads")
  // Resolved threads collapse to a one-line row that expands on click.
  expect(entry).toContain("✓ resolved · ")
  expect(entry).toContain("expandedThreads")
})

test("cm6-entry keeps expand-context and scroll across a threads-only re-render", () => {
  // The slice signature gates the context reset, so a live review_comment push never collapses
  // the reader's expanded region, and the doc is only replaced when the TEXT actually changed.
  expect(entry).toContain("diffRegionSignature")
  expect(entry).toContain("const textChanged = view.state.doc.toString() !== slice")
  expect(entry).toContain("const preservedScrollTop = view.scrollDOM.scrollTop")
})
