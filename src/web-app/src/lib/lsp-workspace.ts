import type { LSPClient } from "@codemirror/lsp-client"

/** DefaultWorkspace throws if a URI is already open — clear stale entries before remounting editors. */
export function closeAllWorkspaceFiles(client: LSPClient): void {
  const files = [...client.workspace.files]
  for (const file of files) {
    try {
      const view = file.getView()
      if (view) client.workspace.closeFile(file.uri, view)
      else client.didClose(file.uri)
    } catch (err) {
      console.warn("closeAllWorkspaceFiles", file.uri, err)
    }
  }
  // Destroyed editors sometimes leave orphaned workspace rows (getView() null).
  const ws = client.workspace as { files: { uri: string }[] }
  if (ws.files.length > 0) {
    for (const f of [...ws.files]) {
      try { client.didClose(f.uri) } catch { /* ignore */ }
    }
    ws.files.length = 0
  }
}
