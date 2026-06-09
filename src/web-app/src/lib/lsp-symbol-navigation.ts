import type * as lsp from "vscode-languageserver-protocol"
import { EditorView } from "@codemirror/view"
import type { Extension, Text } from "@codemirror/state"
import { getLSPPlugin } from "@/lib/lsp-plugin"

export type SymbolLocation = lsp.Location

export type SymbolNavigationAction =
  | { kind: "none" }
  | { kind: "navigate"; location: SymbolLocation }
  | { kind: "list"; title: string; locations: SymbolLocation[] }

export interface SymbolNavigationHandlers {
  clearLocations(): void
  navigate(location: SymbolLocation): void
  showLocations(title: string, locations: SymbolLocation[]): void
}

function comparePosition(a: lsp.Position, b: lsp.Position): number {
  return a.line - b.line || a.character - b.character
}

export function isDeclarationLocation(
  currentUri: string,
  position: lsp.Position,
  definitions: readonly SymbolLocation[],
): boolean {
  return definitions.some((definition) =>
    definition.uri === currentUri
    && comparePosition(position, definition.range.start) >= 0
    && comparePosition(position, definition.range.end) <= 0
  )
}

export function locationsAction(
  label: "usage" | "implementation" | "definition",
  locations: SymbolLocation[],
  alwaysList = false,
): SymbolNavigationAction {
  if (locations.length === 0) return { kind: "none" }
  if (locations.length === 1 && !alwaysList) {
    return { kind: "navigate", location: locations[0] }
  }
  const plural = locations.length === 1 ? label : `${label}s`
  return { kind: "list", title: `${locations.length} ${plural}`, locations }
}

export function uriToWorkdirPath(uri: string, workdir: string): string | null {
  if (!uri.startsWith("file://")) return null
  let path: string
  try {
    path = decodeURIComponent(uri.slice("file://".length))
  } catch {
    return null
  }
  const root = workdir.replace(/\/+$/, "")
  if (path === root) return ""
  return path.startsWith(`${root}/`) ? path.slice(root.length + 1) : null
}

export function lspPositionToOffset(doc: Text, position: lsp.Position): number {
  const line = doc.line(Math.min(Math.max(position.line + 1, 1), doc.lines))
  return Math.min(line.from + Math.max(position.character, 0), line.to)
}

function dispatchAction(action: SymbolNavigationAction, handlers: SymbolNavigationHandlers): void {
  if (action.kind === "navigate") handlers.navigate(action.location)
  else if (action.kind === "list") handlers.showLocations(action.title, action.locations)
}

function asLocations(value: lsp.Location | lsp.Location[] | null): SymbolLocation[] {
  return value ? (Array.isArray(value) ? value : [value]) : []
}

async function requestLocations<P>(
  plugin: ReturnType<typeof getLSPPlugin> & {},
  method: string,
  params: P,
): Promise<SymbolLocation[]> {
  const response = await plugin.client.request<P, lsp.Location | lsp.Location[] | null>(method, params)
  return asLocations(response)
}

async function navigateFromPosition(
  view: EditorView,
  offset: number,
  handlers: SymbolNavigationHandlers,
): Promise<void> {
  const plugin = getLSPPlugin(view)
  if (!plugin) return

  handlers.clearLocations()
  plugin.client.sync()
  const client = plugin.client as typeof plugin.client & {
    hasCapability?: (capability: keyof lsp.ServerCapabilities) => boolean | null
  }
  const lacksCapability = (capability: keyof lsp.ServerCapabilities) =>
    client.hasCapability?.(capability) === false
  const position = plugin.toPosition(offset)
  const textDocument = { uri: plugin.uri }
  const definitions = lacksCapability("definitionProvider")
    ? []
    : await requestLocations(plugin, "textDocument/definition", { textDocument, position })

  if (isDeclarationLocation(plugin.uri, position, definitions)) {
    if (lacksCapability("referencesProvider")) return
    const usages = await requestLocations(plugin, "textDocument/references", {
      textDocument,
      position,
      context: { includeDeclaration: false },
    })
    dispatchAction(locationsAction("usage", usages, true), handlers)
    return
  }

  const implementations = lacksCapability("implementationProvider")
    ? []
    : await requestLocations(plugin, "textDocument/implementation", { textDocument, position })
  const action = implementations.length > 0
    ? locationsAction("implementation", implementations)
    : locationsAction("definition", definitions)
  dispatchAction(action, handlers)
}

export function symbolNavigation(handlers: SymbolNavigationHandlers): Extension {
  return EditorView.domEventHandlers({
    mousedown(event, view) {
      if (event.button !== 0 || (!event.ctrlKey && !event.metaKey)) return false
      if (!(event.target instanceof Node) || !view.contentDOM.contains(event.target)) return false
      const offset = view.posAtCoords({ x: event.clientX, y: event.clientY })
      if (offset == null) return false

      event.preventDefault()
      void navigateFromPosition(view, offset, handlers).catch((error) => {
        getLSPPlugin(view)?.reportError("Symbol navigation failed", error)
      })
      return true
    },
  })
}
