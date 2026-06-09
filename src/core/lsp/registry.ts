import { SERVERS, extOf, launchCommand, type LspServerSpec } from "./catalog"
import {
  customToServerSpec,
  isLspServerEnabled,
  listCustomServerDefs,
  type CustomLspServerDef,
  type EditorConfig,
} from "../settings/editor-config"

export { launchCommand }

/** Built-in + user-defined servers (custom first — overrides catalog on same extension). */
export function allServerSpecs(cfg?: EditorConfig): LspServerSpec[] {
  const custom = listCustomServerDefs(cfg).map(({ id, def }) => customToServerSpec(id, def))
  return [...custom, ...SERVERS]
}

export function getServerById(id: string, cfg?: EditorConfig): LspServerSpec | undefined {
  return allServerSpecs(cfg).find((s) => s.id === id)
}

export function resolveServerForPath(path: string, cfg?: EditorConfig): LspServerSpec | undefined {
  const ext = extOf(path)
  if (!ext) return undefined
  for (const spec of allServerSpecs(cfg)) {
    if (!isLspServerEnabled(spec.id, cfg)) continue
    if (spec.extensions.includes(ext)) return spec
  }
  return undefined
}

export function languageIdForPath(path: string, cfg?: EditorConfig): string {
  const spec = resolveServerForPath(path, cfg)
  if (spec?.languageId) return spec.languageId
  const ext = extOf(path)
  const builtins: Record<string, string> = {
    ".ts": "typescript", ".mts": "typescript", ".cts": "typescript", ".tsx": "typescriptreact",
    ".js": "javascript", ".mjs": "javascript", ".cjs": "javascript", ".jsx": "javascriptreact",
    ".py": "python", ".pyi": "python", ".go": "go", ".rs": "rust", ".dart": "dart",
    ".sh": "shellscript", ".bash": "shellscript", ".yaml": "yaml", ".yml": "yaml",
    ".json": "json", ".jsonc": "jsonc", ".css": "css", ".scss": "scss", ".less": "less",
    ".html": "html", ".htm": "html",
  }
  return builtins[ext] ?? (ext.slice(1) || "plaintext")
}

export type { CustomLspServerDef, LspServerSpec }
