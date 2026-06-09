import { autocompletion, startCompletion, type CompletionContext, type CompletionResult } from "@codemirror/autocomplete"
import {
  LSPPlugin,
  findReferencesKeymap,
  formatKeymap,
  hoverTooltips,
  jumpToDefinitionKeymap,
  renameKeymap,
  serverCompletionSource,
  signatureHelp,
  type LSPClient,
} from "@codemirror/lsp-client"
import { getLSPPlugin } from "@/lib/lsp-plugin"
import { lspDebug } from "@/lib/lsp-debug"
import { symbolNavigation, type SymbolNavigationHandlers } from "@/lib/lsp-symbol-navigation"
import type { Extension } from "@codemirror/state"
import { EditorView } from "@codemirror/view"
import { keymap } from "@codemirror/view"

/** Characters that should open member/import completion. */
const COMPLETION_TRIGGER_CHARS = new Set([".", ":", '"', "'", "`", "<", "/", "@", "#"])

function logCompletionResult(
  phase: string,
  ctx: CompletionContext,
  result: CompletionResult | null,
  extra?: Record<string, unknown>,
): void {
  lspDebug(`completion.${phase}`, {
    explicit: ctx.explicit,
    pos: ctx.pos,
    triggerCh: ctx.state.sliceDoc(ctx.pos - 1, ctx.pos),
    options: result?.options?.length ?? 0,
    from: result?.from,
    to: result?.to,
    ...extra,
  })
}

const loggingCompletionSource = (ctx: CompletionContext) => {
  const plugin = ctx.view ? getLSPPlugin(ctx.view) : null
  lspDebug("completion.request", {
    explicit: ctx.explicit,
    pos: ctx.pos,
    hasPlugin: !!plugin,
    uri: plugin?.uri,
    hasCompletionProvider: plugin
      ? (plugin.client as { hasCapability?: (n: string) => boolean | null }).hasCapability?.("completionProvider") !== false
      : null,
  })
  const raw = serverCompletionSource(ctx)
  if (raw == null) {
    lspDebug("completion.skipped", { reason: "serverCompletionSource returned null" })
    return null
  }
  if (typeof raw === "object" && raw !== null && "then" in raw) {
    return (raw as Promise<CompletionResult | null>).then(
      (r) => { logCompletionResult("async", ctx, r); return r },
      (err) => {
        lspDebug("completion.error", { message: String(err) })
        throw err
      },
    )
  }
  logCompletionResult("sync", ctx, raw as CompletionResult | null)
  return raw
}

function lspAutocompletion(): Extension {
  return autocompletion({
    override: [loggingCompletionSource],
    activateOnTyping: true,
    activateOnTypingDelay: 50,
    interactionDelay: 0,
  })
}

/**
 * Force completion after `.` etc. Sync first, then brief delay so the server
 * sees the latest didChange before textDocument/completion.
 */
function completionOnTriggerChars(): Extension {
  return EditorView.updateListener.of((update) => {
    if (!update.docChanged) return
    const pos = update.state.selection.main.head
    const ch = update.state.sliceDoc(pos - 1, pos)
    if (!COMPLETION_TRIGGER_CHARS.has(ch)) return
    const plugin = getLSPPlugin(update.view)
    if (!plugin) {
      lspDebug("triggerChar.no_plugin", { ch })
      return
    }
    lspDebug("triggerChar", { ch, uri: plugin.uri })
    window.setTimeout(() => {
      if (getLSPPlugin(update.view) !== plugin) return
      plugin.client.sync()
      lspDebug("triggerChar.startCompletion", { ch })
      startCompletion(update.view)
    }, 80)
  })
}

/**
 * Bundled LSP editor extensions. Keep `client.plugin()` as a nested array —
 * same shape as upstream tests (`[other, LSPPlugin.create(...)]`); spreading a
 * pre-flattened list can prevent the ViewPlugin facet from registering.
 */
export function lspEditorExtensions(
  client: LSPClient,
  fileUri: string,
  languageId?: string,
  navigationHandlers?: SymbolNavigationHandlers,
): Extension[] {
  return [
    client.plugin(fileUri, languageId),
    lspAutocompletion(),
    hoverTooltips(),
    signatureHelp(),
    completionOnTriggerChars(),
    navigationHandlers ? symbolNavigation(navigationHandlers) : [],
    keymap.of([
      ...formatKeymap,
      ...renameKeymap,
      ...jumpToDefinitionKeymap,
      ...findReferencesKeymap,
    ]),
  ]
}
