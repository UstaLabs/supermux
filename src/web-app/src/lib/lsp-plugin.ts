import { LSPPlugin } from "@codemirror/lsp-client"
import type { EditorView } from "@codemirror/view"

/**
 * Find the live LSP plugin on an editor view by instance, not facet id.
 * Vite can dedupe poorly so LSPPlugin.get() (view.plugin(lspPlugin)) returns
 * null while the plugin instance is running — breaks sync + completion.
 */
export function getLSPPlugin(view: EditorView): LSPPlugin | null {
  const insts = (view as unknown as { plugins?: Array<{ value: unknown }> }).plugins ?? []
  for (const p of insts) {
    if (p.value instanceof LSPPlugin) return p.value as LSPPlugin
  }
  return null
}

let patched = false

/** Patch once so @codemirror/lsp-client internals (sync, completion, hover) work. */
export function patchLSPPluginGet(): void {
  if (patched) return
  patched = true
  const fallback = LSPPlugin.get.bind(LSPPlugin)
  LSPPlugin.get = (view: EditorView) => getLSPPlugin(view) ?? fallback(view)
}
