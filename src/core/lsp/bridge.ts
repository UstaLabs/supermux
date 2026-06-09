import { LspServerProcess } from "./server"
import { launchCommand, type LspServerSpec } from "./catalog"
import { getServerById, languageIdForPath, resolveServerForPath } from "./registry"
import type { EditorConfig } from "../settings/editor-config"
import { isServerInstalled, prereqMissing } from "./detect"
import { runInstall, type InstallHandle } from "./install"

export type LspServerState = "ready" | "missing" | "prereq-missing"

export interface LspConnectionOpts {
  /** send a frame back to THIS web client (the originating WS connection). */
  send: (frame: any) => void
  /** resolve a session id (UUID) to its absolute workspace dir. */
  getWorkdir: (session: string) => string | undefined
  /** broker editor settings (LSP enable flags, etc.). */
  getEditorConfig?: () => EditorConfig
  log?: (event: string, data?: any) => void
}

// One LSP bridge per web-socket connection. It owns the language-server child
// processes for that editor, routes raw JSON-RPC both ways, and handles the
// detect/install/status flow. Servers are keyed per (session, serverId) so two
// devices editing the same session each get their own — sidestepping LSP
// document-version conflicts. Everything is torn down on dispose().
export class LspConnection {
  private readonly servers = new Map<string, LspServerProcess>()
  private readonly installs = new Map<string, InstallHandle>()

  constructor(private readonly opts: LspConnectionOpts) {}

  handle(frame: any): void {
    switch (frame.type) {
      case "lsp_status_query": return this.onStatusQuery(frame)
      case "lsp_install": return this.onInstall(frame)
      case "lsp_open": return this.onOpen(frame)
      case "lsp_rpc": return this.onRpc(frame)
      case "lsp_close": return this.onClose(frame)
    }
  }

  private key(session: string, serverId: string): string {
    return `${session}:${serverId}`
  }

  private stateOf(spec: LspServerSpec): LspServerState {
    if (isServerInstalled(spec)) return "ready"
    if (spec.install && prereqMissing(spec.install.requires)) return "prereq-missing"
    return "missing"
  }

  // "What server handles this file, and is it installed?" — drives both the
  // editor wiring and the inline install banner.
  private onStatusQuery(frame: any): void {
    const path = String(frame.path ?? "")
    const session = String(frame.session ?? "")
    const cfg = this.opts.getEditorConfig?.()
    const spec = resolveServerForPath(path, cfg)
    if (!spec) {
      this.opts.send({ type: "lsp_status", session, path, supported: false })
      return
    }
    this.opts.send({
      type: "lsp_status",
      session,
      path,
      supported: true,
      serverId: spec.id,
      label: spec.label,
      languageId: languageIdForPath(path, cfg),
      state: this.stateOf(spec),
      installLabel: spec.install?.label ?? null,
      requires: spec.install?.requires ?? null,
    })
  }

  private onInstall(frame: any): void {
    const spec = getServerById(String(frame.serverId ?? ""), this.opts.getEditorConfig?.())
    if (!spec?.install) {
      this.opts.send({ type: "lsp_install_done", serverId: frame.serverId, ok: false, error: "not installable" })
      return
    }
    if (this.installs.has(spec.id)) return // already installing
    this.opts.log?.("lsp_install_start", { serverId: spec.id })
    this.opts.send({ type: "lsp_install_progress", serverId: spec.id, line: `$ ${spec.install.label}` })
    const handle = runInstall(
      spec.install.cmd,
      (line) => this.opts.send({ type: "lsp_install_progress", serverId: spec.id, line }),
      (ok) => {
        this.installs.delete(spec.id)
        const installed = ok && isServerInstalled(spec)
        this.opts.log?.("lsp_install_done", { serverId: spec.id, ok: installed })
        this.opts.send({ type: "lsp_install_done", serverId: spec.id, ok: installed })
      },
      spec.install,
    )
    this.installs.set(spec.id, handle)
  }

  // Spawn (or reuse) the server for a session and start piping. Idempotent.
  private onOpen(frame: any): void {
    const cfg = this.opts.getEditorConfig?.()
    const spec = getServerById(String(frame.serverId ?? ""), cfg)
    if (!spec) return
    const session = String(frame.session ?? "")
    const key = this.key(session, spec.id)
    if (this.servers.has(key)) {
      this.opts.send({ type: "lsp_ready", session, serverId: spec.id })
      return
    }
    const workdir = this.opts.getWorkdir(session)
    if (!workdir) {
      this.opts.send({ type: "lsp_error", session, serverId: spec.id, error: "no workspace for session" })
      return
    }
    if (!isServerInstalled(spec)) {
      this.opts.send({ type: "lsp_error", session, serverId: spec.id, error: "server not installed" })
      return
    }
    const { command, args } = launchCommand(spec)
    this.opts.log?.("lsp_spawn", { serverId: spec.id, command, args, workdir })
    const proc = new LspServerProcess({
      command,
      args,
      cwd: workdir,
      onMessage: (json) => {
        this.traceRpc("s2c", spec.id, json)
        this.opts.send({ type: "lsp_rpc", session, serverId: spec.id, message: json })
      },
      onExit: (code, signal) => {
        this.servers.delete(key)
        this.opts.log?.("lsp_exit", { serverId: spec.id, code, signal })
        this.opts.send({ type: "lsp_exit", session, serverId: spec.id })
      },
      onStderr: (text) => this.opts.log?.("lsp_stderr", { serverId: spec.id, text: text.slice(0, 300) }),
    })
    this.servers.set(key, proc)
    this.opts.send({ type: "lsp_ready", session, serverId: spec.id })
  }

  // Forward a client JSON-RPC message to the right server.
  private onRpc(frame: any): void {
    const proc = this.servers.get(this.key(String(frame.session ?? ""), String(frame.serverId ?? "")))
    if (proc && typeof frame.message === "string") {
      this.traceRpc("c2s", frame.serverId, frame.message)
      proc.write(frame.message)
    }
  }

  // Compact LSP traffic trace (temporary debug aid). Skips the high-frequency
  // didChange chatter so the handshake + completion/diagnostics stand out.
  private traceRpc(dir: "c2s" | "s2c", serverId: string, message: string): void {
    if (!this.opts.log) return
    try {
      const m = JSON.parse(message)
      const tag = m.method ?? (m.id != null ? `response#${m.id}${m.error ? " ERROR" : ""}` : "?")
      if (m.method === "textDocument/didChange") return
      this.opts.log(`lsp_${dir}`, { serverId, tag, error: m.error?.message })
    } catch { /* not json */ }
  }

  private onClose(frame: any): void {
    const key = this.key(String(frame.session ?? ""), String(frame.serverId ?? ""))
    this.servers.get(key)?.kill()
    this.servers.delete(key)
  }

  dispose(): void {
    for (const p of this.servers.values()) p.kill()
    this.servers.clear()
    for (const h of this.installs.values()) h.cancel()
    this.installs.clear()
  }
}
