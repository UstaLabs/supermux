import { defineStore } from "pinia"
import { LSPClient, serverDiagnostics, type Transport } from "@codemirror/lsp-client"
import type { Extension } from "@codemirror/state"
import { lspEditorExtensions } from "@/lib/lsp-editor-extensions"
import { lspDebug } from "@/lib/lsp-debug"
import { closeAllWorkspaceFiles } from "@/lib/lsp-workspace"
import { useWS } from "@/api/ws"
import { beginLspInstall, endLspInstall, tickLspInstall } from "@/lib/lsp-install-toast"
import type { SymbolNavigationHandlers } from "@/lib/lsp-symbol-navigation"

export interface LspStatus {
  path: string
  supported: boolean
  serverId?: string
  label?: string
  languageId?: string
  state?: "ready" | "missing" | "prereq-missing" | "unavailable"
  installLabel?: string | null
  requires?: string | null
  error?: string
}

interface ClientEntry {
  client: LSPClient
  handlers: Set<(v: string) => void>
  ready: Promise<void>
  resolveReady: () => void
}

// Frontend half of the LSP bridge. Owns one CodeMirror LSPClient per
// (session, serverId), a WebSocket-backed Transport for each, and the
// status/install request plumbing. The broker is a dumb pipe; all the real LSP
// protocol (initialize, didOpen, completion requests) happens inside LSPClient.
export const useLsp = defineStore("lsp", () => {
  const clients = new Map<string, ClientEntry>()
  const statusWaiters = new Map<string, ((s: LspStatus) => void)[]>()
  const installWaiters = new Map<string, {
    label: string
    toastId: string
    onLine: (l: string) => void
    resolve: (ok: boolean) => void
  }>()
  const openWaiters = new Map<string, ((ok: boolean) => void)[]>()

  const ws = () => useWS()
  const key = (session: string, serverId: string) => `${session}:${serverId}`
  const statusKey = (session: string, path: string) => `${session}:${path}`

  // Called by ws.ts for every `lsp_*` frame the broker sends.
  function handleFrame(frame: any): void {
    if (frame.type !== "lsp_rpc") {
      lspDebug(`frame.${frame.type}`, {
        session: frame.session,
        path: frame.path,
        serverId: frame.serverId,
        state: frame.state,
        supported: frame.supported,
        error: frame.error,
      })
    }
    switch (frame.type) {
      case "lsp_status": {
        const sk = statusKey(frame.session ?? "", frame.path ?? "")
        const waiters = statusWaiters.get(sk)
        if (waiters) {
          statusWaiters.delete(sk)
          for (const w of waiters) w(frame as LspStatus)
        }
        break
      }
      case "lsp_error": {
        const k = key(frame.session ?? "", frame.serverId ?? "")
        const waiters = openWaiters.get(k)
        if (waiters) {
          openWaiters.delete(k)
          for (const w of waiters) w(false)
        }
        break
      }
      case "lsp_rpc": {
        const entry = clients.get(key(frame.session, frame.serverId))
        if (entry && typeof frame.message === "string") {
          for (const h of entry.handlers) h(frame.message)
        }
        break
      }
      case "lsp_ready": {
        const k = key(frame.session, frame.serverId)
        const open = openWaiters.get(k)
        if (open) {
          openWaiters.delete(k)
          for (const w of open) w(true)
        }
        break
      }
      case "lsp_exit": {
        const k = key(frame.session, frame.serverId)
        const entry = clients.get(k)
        if (entry) {
          try { entry.client.disconnect() } catch { /* already gone */ }
          clients.delete(k)
        }
        break
      }
      case "lsp_install_progress": {
        const w = installWaiters.get(frame.serverId)
        if (!w) break
        const line = String(frame.line ?? "")
        w.onLine(line)
        tickLspInstall(w.toastId, w.label, line)
        break
      }
      case "lsp_install_done": {
        const w = installWaiters.get(frame.serverId)
        if (!w) break
        installWaiters.delete(frame.serverId)
        endLspInstall(w.toastId, w.label, !!frame.ok)
        w.resolve(!!frame.ok)
        break
      }
    }
  }

  function queryStatus(session: string, path: string): Promise<LspStatus> {
    return new Promise((resolve) => {
      const sk = statusKey(session, path)
      const arr = statusWaiters.get(sk) ?? []
      arr.push(resolve)
      statusWaiters.set(sk, arr)
      ws().send({ type: "lsp_status_query", session, path })
      setTimeout(() => {
        const cur = statusWaiters.get(sk)
        if (cur?.includes(resolve)) {
          statusWaiters.set(sk, cur.filter((r) => r !== resolve))
          resolve({ path, supported: true, state: "unavailable", error: "status query timed out" })
        }
      }, 10000)
    })
  }

  async function ensureClient(session: string, serverId: string, workdir: string): Promise<LSPClient> {
    const k = key(session, serverId)
    const existing = clients.get(k)
    if (existing) {
      lspDebug("ensureClient.reuse", { k, session, serverId })
      await existing.ready
      lspDebug("ensureClient.reuse.ready", { k })
      return existing.client
    }
    lspDebug("ensureClient.start", { k, session, serverId, workdir })

    const handlers = new Set<(v: string) => void>()
    const transport: Transport = {
      send: (m) => ws().send({ type: "lsp_rpc", session, serverId, message: m }),
      subscribe: (h) => handlers.add(h),
      unsubscribe: (h) => handlers.delete(h),
    }
    let resolveReady!: () => void
    const ready = new Promise<void>((res) => { resolveReady = res })
    const client = new LSPClient({
      rootUri: pathToDirUri(workdir),
      extensions: [serverDiagnostics()],
      timeout: 15_000,
    })
    clients.set(k, { client, handlers, ready, resolveReady })

    const openOk = await new Promise<boolean>((resolve) => {
      const arr = openWaiters.get(k) ?? []
      arr.push(resolve)
      openWaiters.set(k, arr)
      ws().send({ type: "lsp_open", session, serverId })
      setTimeout(() => {
        const cur = openWaiters.get(k)
        if (cur?.includes(resolve)) {
          openWaiters.set(k, cur.filter((r) => r !== resolve))
          resolve(false)
        }
      }, 12000)
    })

    if (!openOk) {
      clients.delete(k)
      lspDebug("ensureClient.open_failed", { k })
      throw new Error("language server failed to start")
    }

    lspDebug("ensureClient.connect", { k })
    client.connect(transport)
    try {
      await client.initializing
    } catch (err) {
      lspDebug("ensureClient.initialize_failed", { k, message: String(err) })
      throw err
    }
    resolveReady()
    lspDebug("ensureClient.ready", { k, rootUri: pathToDirUri(workdir) })
    return client
  }

  // Resolve the editor extension for a file: the bundled LSP support
  // (completion + diagnostics + hover + signature help) when the server is
  // ready, otherwise an empty extension plus a status describing why.
  async function editorExtension(
    session: string,
    workdir: string,
    path: string,
    navigationHandlers?: SymbolNavigationHandlers,
  ): Promise<{ extension: Extension[]; status: LspStatus }> {
    lspDebug("editorExtension.start", { session, path, workdir })
    const status = await queryStatus(session, path)
    lspDebug("editorExtension.status", { session, path, status })
    if (!status.supported || !status.serverId || status.state !== "ready") {
      return { extension: [], status }
    }
    try {
      const client = await ensureClient(session, serverId(status), workdir)
      const fileUri = pathToUri(joinPath(workdir, path))
      const openBefore = client.workspace.files.length
      closeAllWorkspaceFiles(client)
      if (openBefore > 0) {
        lspDebug("editorExtension.workspaceReset", { session, path, closed: openBefore })
      }
      const ext = lspEditorExtensions(client, fileUri, status.languageId, navigationHandlers)
      lspDebug("editorExtension.ok", { session, path, fileUri, extCount: ext.length, connected: client.connected })
      return { extension: ext, status }
    } catch (err) {
      const message = err instanceof Error ? err.message : String(err)
      lspDebug("editorExtension.error", { session, path, message })
      return {
        extension: [],
        status: { ...status, state: "unavailable", error: message },
      }
    }
  }

  /** Install via WS; progress and result use the shared LSP install snackbar. */
  function install(serverId: string, label: string, onLine?: (line: string) => void): Promise<boolean> {
    return new Promise((resolve) => {
      const toastId = beginLspInstall(label, serverId)
      installWaiters.set(serverId, {
        label,
        toastId,
        onLine: onLine ?? (() => {}),
        resolve,
      })
      ws().send({ type: "lsp_install", serverId })
    })
  }

  return { handleFrame, queryStatus, ensureClient, editorExtension, install }
})

function serverId(s: LspStatus): string { return s.serverId! }

function joinPath(dir: string, rel: string): string {
  return `${dir.replace(/\/+$/, "")}/${rel.replace(/^\/+/, "")}`
}

// Absolute filesystem path → file:// URI, percent-encoding each segment but
// preserving the slashes (LSP servers compare URIs literally).
function pathToUri(absPath: string): string {
  return "file://" + absPath.split("/").map(encodeURIComponent).join("/")
}

function pathToDirUri(absPath: string): string {
  const uri = pathToUri(absPath.replace(/\/+$/, ""))
  return uri.endsWith("/") ? uri : `${uri}/`
}
