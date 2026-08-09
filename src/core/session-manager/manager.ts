import { RuntimeRegistry, type SessionRuntime } from "./runtime"
import { deliverInbound, type InboundDeliveryResult } from "./inbound-delivery"
import { RecentInboundIds } from "./recent-inbound-ids"
import { isPersistentRuntimeSession } from "./types"
import type { Registry, ProxyEntry, Session } from "./registry"
import type { AgentAdapter } from "../agents/types"
import { ClaudeCodeAdapter } from "../agents/claude"
import { CodexAdapter } from "../agents/codex/adapter"
import type { CodexSpawnHandle } from "../agents/codex/spawn"
import { CursorAdapter } from "../agents/cursor/adapter"
import { OpenCodeAdapter } from "../agents/opencode/adapter"
import type { OpenCodeSpawnHandle } from "../agents/opencode/spawn"
import { GrokAdapter } from "../agents/grok/adapter"
import { agents } from "../agents/registry"
import type { ResumeCtx, ResumeRow } from "../agents/session-types"
import { PendingReapply, shouldDeferReapply, changedSince, type PreChangeConfig } from "./pending-reapply"
import { clampSessionReasoningLevel } from "../models/session-agent-settings"
import { supportedReasoningLevels } from "../models/reasoning-levels"
import type { ModelInfo } from "../models/discovery"
import { buildClaudeSpawnSpec } from "./spawn-command"
import { preAcceptTrust } from "./trust"
import { sendChannelConsentEnter } from "./post-spawn-keys"
import { ensureUnique, resolveSelfRename } from "./naming"
import { randomBytes } from "crypto"
import { resumedSessionPid } from "./resume-pid"
import type { SessionBackend } from "../runtime/session-backend"
import { AgentKind, isAgentKind } from "../../shared/agents"
import { wireClaudeStateEvents } from "../agents/claude/state-projection"
import { claudeTranscriptPath } from "../agents/claude/transcript-path"
import { isDraftSession } from "./supervisor"
import { isWorktreeReclaimable } from "../worktree/gc"
import { removeWorktree } from "../worktree/manager"
import { transformOutbound } from "../routing"
import { resolveDownloadAttachment, type DownloadableApi } from "./download"
import { propagateSessionRename } from "../workspace/name"
import { renderTranscript } from "../search/transcript-render"
import { buildProxyPublicUrl } from "../../channels/web/proxy"
import { listDevices } from "../display/scrcpy/adb"
import { INBOX_DIR } from "../../shared/paths"
import type { RegisterReply, OpResult } from "./socket-server"
import type { RegisterFrame, OutboundFrame, OrchestrationFrame } from "../../shared/socket-frames"
import type { Channel, OutboundAction } from "../../channels/channel"
import type { FileStore } from "../files/store"
import type { Db } from "../storage/db"
import type { MessageStore } from "./messages"
import type { SearchStore } from "../search/store"
import type { CommandRegistry } from "../slash-commands"
import type { AgentStateStore, AgentPhase } from "./agent-state-store"
import type { BackgroundTaskStore } from "./background-task-store"
import type { TerminalManager } from "../terminal/manager"
import type { DisplayManager } from "../display/manager"
import type { FsWatcher } from "../editor/fs-watcher"
import type { ProxyStatus } from "../proxy/liveness"
import type { SpawnResult } from "./spawn-helper"
import type { WorkspaceDto } from "../workspace/dto"
import type { ProviderName } from "../display/types"
import { makeLogger } from "../../shared/log"

const log = makeLogger("session-manager")

const SOUL_SETUP_AUTO_SEND_DELAY_MS = 3_000

/**
 * Narrow ports into the rest of the broker. Injected ONCE at construction —
 * never per-call deps bags. Members that main.ts `let`-assigns after
 * construction (webChannel, agentRpc, the socket server) are lazy thunks or
 * late-binding closures: capturing the value at construction time would
 * capture undefined.
 */
export type SessionManagerPorts = {
  /** `let webChannel` in main.ts is assigned after construction — deref lazily, never capture. */
  getWebChannel: () => { broadcastToAll(frame: object): void } | undefined
  /** `let agentRpc` in main.ts is assigned after construction — deref lazily, never capture. */
  getAgentRpc: () => { settle(requestId: string, data: unknown): void; fail(requestId: string, error: string): void }
  /** The socket server is constructed WITH these handlers, so it is late-bound too.
   *  sendInbound is the claude shim transport — component-internal. Every other
   *  sender goes through SessionManager.deliver, the one kind-aware door. */
  socket: {
    sendInbound(session_id: string, payload: { content: string; meta: Record<string, string> }): Promise<void>
  }
  /** Collaborators of the deliver() funnel. */
  inbound: {
    /** Fired after a successful hand-off (incl. an idempotent re-send) so the
     *  broker can re-broadcast the session's CURRENT agent_state — clears the
     *  client's "Sending…" bubble even if the turn-start hook is dropped. */
    onDelivered?: (sessionId: string) => void
  }
  /** Claude's persistent-terminal runtime (tmux window addressing). */
  backend: {
    /** Heals a missing tmux_window_id via a name→id resolve; lives in main.ts because the resume paths use it too. */
    runtimeTargetIdOf(s: { id: string; name: string; tmux_window_id?: string }): Promise<string | null>
    kill(targetId: string): Promise<void>
  }
  /** Per-session teardown collaborators (the kill/unregister ladder). */
  cleanup: {
    terminals: Pick<TerminalManager, "killAllForSession">
    fsWatcher: Pick<FsWatcher, "killSession">
    stopClaudeTailer(sessionUuid: string): void
    releaseDraftAttachments(payload: { attachments?: Array<{ file_id?: string }> } | null | undefined): void

    /** gitStatusService.sync over the current visible-session set. */
    syncGitStatus(): void
  }
  /** Display streams: teardown on kill AND the start/stop/get orchestration ops. */
  displays: Pick<DisplayManager, "killAllForSession" | "start" | "get" | "stop">
  agentState: Pick<AgentStateStore, "applyEvent" | "clear" | "get">
  bgTasks: Pick<BackgroundTaskStore, "clear">
  commands: Pick<CommandRegistry, "remove" | "refresh">
  /** Model/effort switching (applyConfig): the model cache lives in main.ts. */
  config: {
    lookupModels(agent: AgentKind): ModelInfo[]
  }
  /** Register/attach-path collaborators. */
  register: {
    interruptClaudePane(sessionId: string): Promise<void>
    notifyAgentError(sessionId: string, sessionName: string, errorType: string, errorMessage: string): Promise<void>
    ensureClaudeTailer(sessionUuid: string, name: string, workdir: string, seekToEnd?: boolean): void
    maybeAutoSendSoulSetup(sessionId: string): Promise<void>
  }
  /** Outbound delivery (shim reply/react/edit ops). */
  outbound: {
    onAssistantMessage(
      sessionId: string,
      ev: { text: string; chat_id?: string; reply_to?: string; files?: string[]; format?: "text" | "markdownv2"; keyboard?: string[] },
    ): Promise<void>
    getChannel(name: string): Channel | undefined
    /** Boot-time constant: telegram token/getFile when telegram is configured. */
    telegramApi: DownloadableApi | undefined
  }
  /** Orchestration-op collaborators that stay in main.ts (spawn moves in with PR 3). */
  orchestration: {
    spawnSession(args: { workdir: string; requestedName?: string; agent?: AgentKind }): Promise<SpawnResult>
    refreshTelegramMenu(): Promise<void>
    wsDto(id: string): WorkspaceDto | undefined
    exposedProxyLinksBaseUrl(): string | undefined
    proxyWsPayload(entry: ProxyEntry, status?: ProxyStatus): object
    proxyLiveness: { getStatus(domain: string): ProxyStatus; refresh(): Promise<void> }
  }
  stores: {
    fileStore: FileStore
    messageLog: Pick<MessageStore, "get" | "update" | "addReaction">
    searchStore: Pick<SearchStore, "searchKnowledge" | "searchSessions">
    db: Db
  }
  /** Resume-flow collaborators that stay in main.ts (the rest are module imports). */
  resume: {
    /** Socket bind — the server is constructed after the component (late-bound). */
    bind(sessionId: string): Promise<void>
    /** Recreate a merged-away worktree at the SAME path before any respawn. */
    ensureSessionWorktree(session: { id: string; name: string; workdir: string; repo_root?: string | null; session_branch?: string | null; base_branch?: string | null }): Promise<void>
    /** Resolved CLI effort for a session (model cache lives in main.ts). */
    sessionEffort(s: { agent?: string; model?: string; reasoningLevel?: string }): string | undefined
    resolveAttachment(file_id: string): Promise<string>
    /** Adapter event fan-out (activity/state/commands sinks live in main.ts). */
    wireAdapterEvents(adapter: AgentAdapter, sessionId: string): void
    sessionBackend: SessionBackend
    tmuxSession: string
  }
}

/**
 * The component that owns per-session runtime state (Move 2 of the
 * session-consolidation spec). It grows stage by stage: it owns the ONE
 * runtime store, the kill/unregister flow, and the socket handlers;
 * spawn/resume arrive with the resume unification (PR 3).
 *
 * Rules: no agent-kind checks leak OUT of this layer into services, and no
 * shared broker state lives anywhere else. Collaborators are injected once at
 * construction — never per-call deps bags.
 */
export class SessionManager {
  readonly registry: Registry
  readonly runtimes = new RuntimeRegistry()
  /** Sessions owing a deferred model/effort apply (marked mid-turn, drained on idle). */
  private readonly pendingReapply = new PendingReapply()
  /** Dedupe window for inbound message_ids — owned here so deliver() is idempotent. */
  readonly recentInbound = new RecentInboundIds()
  private readonly ports: SessionManagerPorts

  constructor(registry: Registry, ports: SessionManagerPorts) {
    this.registry = registry
    this.ports = ports
  }

  adapterFor(sessionId: string): AgentAdapter | undefined {
    return this.runtimes.get(sessionId)?.adapter
  }

  /**
   * THE one inbound door. Routes a user turn to the session's agent by KIND:
   * adapter.send() for adapter-driven agents (codex/cursor/opencode/grok), the
   * claude shim socket otherwise. Every broker-side sender (channels, curator,
   * soul-setup, agent-rpc, reviews) must call this — never the raw socket
   * sendInbound, which is claude-only transport and silently queues-then-drops
   * frames for every other kind.
   */
  deliver(sessionId: string, text: string, meta: any): Promise<InboundDeliveryResult> {
    return deliverInbound({
      getAdapter: (id) => this.runtimes.get(id)?.adapter,
      isClaude: (id) => {
        const s = this.registry.get(id)
        // No row → preserve the historical claude default (`agent ?? "claude"`).
        return s ? isPersistentRuntimeSession(s) : true
      },
      sendInboundSocket: (id, payload) => this.ports.socket.sendInbound(id, payload),
      seen: this.recentInbound,
      onDelivered: (id) => this.ports.inbound.onDelivered?.(id),
    }, sessionId, text, meta)
  }

  /**
   * Kind-aware "can a turn land right now": a persistent-runtime (claude)
   * session is deliverable once a shim reports CONNECTED; adapter-driven kinds
   * once their adapter is registered. Readiness waits must use this — polling
   * `connected` alone is claude-shaped and never comes true for e.g. a codex
   * session that has no channel shim.
   */
  isDeliverable(sessionId: string): boolean {
    const s = this.registry.get(sessionId)
    if (!s) return false
    return isPersistentRuntimeSession(s) ? !!s.connected : !!this.adapterFor(sessionId)
  }

  /** Poll isDeliverable until it turns true or timeoutMs elapses. */
  async waitDeliverable(sessionId: string, timeoutMs: number, pollMs = 100): Promise<boolean> {
    const deadline = Date.now() + timeoutMs
    while (Date.now() < deadline) {
      if (this.isDeliverable(sessionId)) return true
      await new Promise<void>(r => setTimeout(r, pollMs))
    }
    log.warn("wait_deliverable_timeout", { sessionId, timeoutMs })
    return false
  }

  registerRuntime(sessionId: string, runtime: SessionRuntime): void {
    this.runtimes.set(sessionId, runtime)
  }

  deleteRuntime(sessionId: string): void {
    this.runtimes.delete(sessionId)
  }

  registerClaudeRuntime(sessionId: string, adapter: ClaudeCodeAdapter): void {
    this.registerRuntime(sessionId, { kind: AgentKind.Claude, adapter })
  }

  registerCodexRuntime(sessionId: string, name: string, adapter: CodexAdapter, handle: CodexSpawnHandle): void {
    this.registerRuntime(sessionId, { kind: AgentKind.Codex, adapter, handle })
    handle.onExit?.((code: number | null) => {
      log.info("codex_app_server_exited", { name, code })
      this.deleteRuntime(sessionId)
    })
  }

  registerCursorRuntime(sessionId: string, adapter: CursorAdapter): void {
    this.registerRuntime(sessionId, { kind: AgentKind.Cursor, adapter })
  }

  // grok's stdio child is owned by the adapter (no separate handle), so unlike
  // opencode there's no handle.onExit to unregister on — adapter.stop() is the kill.
  registerGrokRuntime(sessionId: string, adapter: GrokAdapter): void {
    this.registerRuntime(sessionId, { kind: AgentKind.Grok, adapter })
  }

  registerOpenCodeRuntime(sessionId: string, name: string, adapter: OpenCodeAdapter, handle: OpenCodeSpawnHandle): void {
    this.registerRuntime(sessionId, { kind: AgentKind.OpenCode, adapter, handle })
    handle.onExit?.((code: number | null) => {
      log.info("opencode_serve_exited", { name, code })
      this.deleteRuntime(sessionId)
    })
  }

  async kill(id: string): Promise<void> {
    const s = this.registry.get(id)
    if (!s) return

    // A draft is a cached session row with no process, no tmux window, and no
    // proxies. Deleting it must DISCARD (hard-delete) the row — never archive it
    // to user_status='settled', which would leave a phantom settled session.
    if (isDraftSession(s)) {
      this.ports.cleanup.releaseDraftAttachments(s.draft_payload)
      this.registry.sessions.deleteById(s.id)
      this.ports.getWebChannel()?.broadcastToAll({ type: "session_removed", id: s.id })
      return
    }

    const displayName = s.name

    await this.ports.cleanup.terminals.killAllForSession(displayName)
    void this.ports.displays.killAllForSession(displayName)
    this.ports.cleanup.fsWatcher.killSession(displayName)

    const removedProxies = this.registry.removeProxiesForSession(s.id)
    if (removedProxies.length > 0) {
      for (const domain of removedProxies) {
        this.ports.getWebChannel()?.broadcastToAll({ type: "proxy_removed", domain })
      }
    }

    if (s.agent === "claude") {
      const wid = await this.ports.backend.runtimeTargetIdOf(s)
      if (wid) await this.ports.backend.kill(wid)
      else log.warn("kill_session_no_runtime_target", { name: displayName })
    } else if (s.agent === "codex") {
      const runtime = this.runtimes.get(s.id)
      if (runtime?.kind === AgentKind.Codex) runtime.handle.kill()
    } else if (s.agent === AgentKind.Cursor) {
      // No persistent process or tmux pane to kill.
    } else if (s.agent === "opencode") {
      const runtime = this.runtimes.get(s.id)
      if (runtime?.kind === AgentKind.OpenCode) runtime.handle.kill()
    } else if (s.agent === AgentKind.Grok) {
      // The `grok agent stdio` child is owned by the adapter, so stop() is the kill.
      const runtime = this.runtimes.get(s.id)
      if (runtime?.kind === AgentKind.Grok) void runtime.adapter.stop()
    }
    this.deleteRuntime(s.id)
    this.ports.cleanup.stopClaudeTailer(s.id)   // also clears the session's background tasks
    this.ports.agentState.clear(s.id)
    this.recentInbound.clear(s.id)
    this.pendingReapply.clear(s.id)
    // Do NOT delete agent_home — needed for resume

    // Reclaim this session's worktree if it has no unsaved/unmerged work; otherwise
    // keep it (recoverable). Only on kill — never on suspend.
    if (s.repo_root && s.session_branch && s.workdir) {
      if (isWorktreeReclaimable(s.workdir, s.session_branch, s.base_branch || "HEAD")) {
        await removeWorktree(s.repo_root, s.workdir, s.session_branch).catch(() => {})
      } else {
        log.warn("worktree_kept_unclean", { id, workdir: s.workdir })
      }
    }
  }

  unregister(id: string): void {
    const s = this.registry.get(id)
    this.registry.unregister(id)  // archives the session (resumable via resumeFromArchive)
    if (s) this.deleteRuntime(s.id)
    this.ports.commands.remove(id)
    this.ports.agentState.clear(id)  // drop any lingering working/dead state for the now-archived session
    this.ports.bgTasks.clear(id)      // archived sessions cannot be "waiting"
    // NOTE: do NOT delete agent_home here — archived sessions are resumable, so
    // their home (cursor runtime symlink + per-session state/history) must
    // survive. Truly orphaned dirs (no registry entry) are reclaimed by the
    // startup orphan-GC instead.
    this.ports.cleanup.syncGitStatus()  // release fs-watch for the now-archived session
  }

  async handleRegister(msg: RegisterFrame & { session_id: string }): Promise<RegisterReply> {
    const sessionUuid = msg.session_id as string  // UUID from MUX_SESSION_ID
    const requested = msg.requested_name as string | undefined
    const workdir = msg.workdir as string
    const agentSessionId = msg.agent_session_id as string | undefined

    // Attach path: the spawn path created the row before claude started, so
    // every legitimate register frame finds it here (first connect and
    // reconnect look identical).
    const existing = this.registry.get(sessionUuid)
    if (existing) {
      log.info("shim_attach", { name: existing.name, id: sessionUuid, old_pid: existing.pid, new_pid: msg.pid })
      if (agentSessionId) {
        this.registry.sessions.setAgentSessionId(sessionUuid, agentSessionId)
      }
      // Rebuild adapter if missing (first connect, or after broker restart)
      if (!this.runtimes.has(sessionUuid) && (existing.agent ?? "claude") === "claude") {
        const adapter = new ClaudeCodeAdapter({
          sessionName: existing.name,
          workdir: existing.workdir,
          sendInboundSocket: (payload) => this.ports.socket.sendInbound(sessionUuid, payload),
          interruptSocket: () => this.ports.register.interruptClaudePane(sessionUuid),
        })
        this.registerClaudeRuntime(sessionUuid, adapter)
        wireClaudeStateEvents(adapter, {
          onState: (event, tool) => this.ports.agentState.applyEvent(sessionUuid, event, tool),
          onError: (errorType, message) => {
            const s = this.registry.get(sessionUuid)
            void this.ports.register.notifyAgentError(sessionUuid, s?.name ?? sessionUuid, errorType, message)
          },
        })
      }
      if (existing.status === "suspended") {
        this.registry.sessions.activate(sessionUuid, msg.pid as number)
      }
      this.ports.register.ensureClaudeTailer(sessionUuid, existing.name, existing.workdir, true)
      void this.ports.commands.refresh(existing.name)
      if (existing.role === "personal_assistant" && existing.is_default) {
        setTimeout(() => { void this.ports.register.maybeAutoSendSoulSetup(existing.id) }, SOUL_SETUP_AUTO_SEND_DELAY_MS)
      }
      return { name: existing.name, session_id: sessionUuid }
    }

    // No row → nothing to attach to. The spawn path creates every legitimate
    // row before claude starts, and the shim can only reach a socket the
    // broker bound for a spawn. An unknown id means the session was killed
    // in the ~1s startup gap. Refuse; never create rows here.
    log.warn("register_unknown_session", { session_id: sessionUuid, requested, workdir })
    throw new Error(`unknown session: ${sessionUuid} — the broker did not spawn this session`)
  }

  async handleOutbound(msg: OutboundFrame & { session_id: string }): Promise<OpResult> {
    const fromSession = msg.session_id
    // fromSession is now UUID; adapters are keyed by UUID
    // Resolve channel from chat_id, falling back to telegram for legacy
    // (pre-namespacing) values held by long-lived shim sessions across
    // a broker upgrade.
    function resolveChannel(rawChatId: string): { channelName: string; chat_id: string } {
      if (!rawChatId.includes(":")) return { channelName: "telegram", chat_id: `telegram:${rawChatId}` }
      return { channelName: rawChatId.split(":", 1)[0]!, chat_id: rawChatId }
    }
    try {
      const op = msg.op
      if (op.name === "reply") {
        const adapter = this.runtimes.get(fromSession)?.adapter
        const files = optionalStringArrayArg(op.args, "files")
        const hasFiles = !!files?.length
        // Claude uses reply for all user-facing output. Codex/cursor normally
        // stream assistant text, but allow reply when delivering outbound files
        // (e.g. screen recordings) that cannot be attached via the text stream.
        if (!adapter || (adapter.kind !== "claude" && !hasFiles)) {
          const kind = adapter?.kind ?? "unknown"
          const hint = kind === "codex" || kind === "cursor"
            ? " — use your normal assistant output for text; reply is only for files[]"
            : ""
          return { ok: false, error: `reply not allowed from agent kind ${kind}${hint}` }
        }
        // Synchronously await onAssistantMessage so the shim's reply tool
        // call doesn't return until the channel send completes.
        try {
          await this.ports.outbound.onAssistantMessage(fromSession, {
            text: stringArg(op.args, "text"),
            chat_id: stringArg(op.args, "chat_id"),
            reply_to: optionalStringArg(op.args, "reply_to"),
            files,
            format: optionalFormatArg(op.args, "format"),
            keyboard: optionalStringArrayArg(op.args, "keyboard"),
          })
          return { ok: true, value: { message_id: undefined } }
        } catch (err) {
          return { ok: false, error: String(err instanceof Error ? err.message : err) }
        }
      } else if (op.name === "react") {
        const rawChatId = stringArg(op.args, "chat_id")
        const messageId = stringArg(op.args, "message_id")
        const emoji = stringArg(op.args, "emoji")
        const { channelName, chat_id } = resolveChannel(rawChatId)
        const ch = this.ports.outbound.getChannel(channelName)
        if (!ch) return { ok: false, error: `unknown channel for chat_id ${chat_id}` }
        const initial: OutboundAction = { op: "react", chat_id, message_id: messageId, emoji }
        const action = await transformOutbound(initial, fromSession, ch.capabilities, this.ports.stores.fileStore, this.registry)
        if (action.op !== "react") return { ok: false, error: "transformOutbound dropped react" }
        const res = await ch.send(action)
        if (res.ok) {
          this.ports.stores.messageLog.addReaction(fromSession, `out:${chat_id}:${messageId}`, emoji, new Date().toISOString())
        }
        return res.ok ? { ok: true, value: res.value } : { ok: false, error: res.error }
      } else if (op.name === "edit_message") {
        const rawChatId = stringArg(op.args, "chat_id")
        const messageId = stringArg(op.args, "message_id")
        const { channelName, chat_id } = resolveChannel(rawChatId)
        const ch = this.ports.outbound.getChannel(channelName)
        if (!ch) return { ok: false, error: `unknown channel for chat_id ${chat_id}` }
        const initial: OutboundAction = { op: "edit_message", chat_id, message_id: messageId, text: stringArg(op.args, "text"), format: optionalFormatArg(op.args, "format") }
        const action = await transformOutbound(initial, fromSession, ch.capabilities, this.ports.stores.fileStore, this.registry)
        if (action.op !== "edit_message") return { ok: false, error: "transformOutbound dropped edit_message" }
        const res = await ch.send(action)
        if (res.ok) {
          this.ports.stores.messageLog.update(fromSession, `out:${chat_id}:${messageId}`, { text: action.text, edited_at: new Date().toISOString() })
        }
        return res.ok ? { ok: true, value: res.value } : { ok: false, error: res.error }
      } else if (op.name === "download_attachment") {
        try {
          const fileId = stringArg(op.args, "file_id")
          const r = await resolveDownloadAttachment({
            file_id: fileId,
            fileStore: this.ports.stores.fileStore,
            telegramApi: this.ports.outbound.telegramApi,
            inboxDir: INBOX_DIR,
          })
          log.info(r.via === "filestore" ? "download.completed.synthetic" : "download.completed", {
            session: fromSession, file_id: fileId, path: r.path,
          })
          return { ok: true, value: { path: r.path } }
        } catch (err: any) {
          return { ok: false, error: String(err?.message ?? err) }
        }
      }
      return { ok: false, error: `unknown op: ${op.name}` }
    } catch (err) {
      return { ok: false, error: String(err instanceof Error ? err.message : err) }
    }
  }

  async handleOrchestration(msg: OrchestrationFrame & { session_id: string }): Promise<OpResult> {
    // Permission check — fromSession is UUID
    const fromSession = msg.session_id
    const s = this.registry.get(fromSession)  // Look up by UUID
    const op = msg.op
    const NO_ORCHESTRATE_REQUIRED = new Set(["rename_session", "expose_port", "unexpose_port", "set_proxy_public", "start_display", "stop_display", "list_devices", "rpc_resolve", "rpc_reject", "memory_search", "find_sessions", "read_session"])
    if (!s?.can_orchestrate && !NO_ORCHESTRATE_REQUIRED.has(op.name)) {
      return { ok: false, error: "permission denied (can_orchestrate=false)" }
    }
    try {
    switch (op.name) {
      case "spawn_session":  {
        try {
          const requestedAgent = op.args.agent
          if (requestedAgent != null && !isAgentKind(requestedAgent)) {
            return { ok: false, error: `unknown agent kind: ${String(requestedAgent)}` }
          }
          const agent = requestedAgent ?? undefined
          const r = await this.ports.orchestration.spawnSession({ workdir: stringArg(op.args, "workdir"), requestedName: optionalStringArg(op.args, "name"), agent })
          await this.ports.orchestration.refreshTelegramMenu()
          // Notify web clients so the session list updates immediately.
          const entry = this.registry.get(r.session_id)
          if (entry) {
            this.ports.getWebChannel()?.broadcastToAll({
              type: "session_added",
              session: { id: entry.id, name: entry.name, workdir: entry.workdir, mute: !!entry.mute, connected: true, agent: entry.agent, model: entry.model, repo_root: entry.repo_root || undefined, session_branch: entry.session_branch || undefined, finish_job: entry.finish_job, user_status: entry.user_status, sort_order: entry.sort_order, draft_payload: entry.draft_payload },
            })
          }
          // Auto-bind the requesting chat to the new session if the
          // caller included a chat_id.  Claude sessions register
          // asynchronously via socket, so poll until the session
          // appears in the registry before calling setActive.
          const chatId = optionalStringArg(op.args, "chat_id")
          if (chatId) {
            const pollSetActive = async (sessionId: string, chatId: string, attempts = 60) => {
              for (let i = 0; i < attempts; i++) {
                if (this.registry.get(sessionId)) {
                  this.registry.setActive(chatId, sessionId)
                  return
                }
                await new Promise(res => setTimeout(res, 50))
              }
              log.warn("spawn_session_route_timeout", { sessionId, chatId })
            }
            pollSetActive(r.session_id, chatId)
          }
          return { ok: true, value: r }
        } catch (err: any) {
          return { ok: false, error: String(err?.message ?? err) }
        }
      }
      case "kill_session": {
        const name = stringArg(op.args, "name")
        const killed = this.registry.resolveName(name)
        if (killed) {
          await this.kill(killed.id)
          this.unregister(killed.id)
          await this.ports.orchestration.refreshTelegramMenu()
          this.ports.getWebChannel()?.broadcastToAll({ type: "session_removed", id: killed.id })
        }
        return { ok: true, value: "killed" }
      }
      case "rename_session": {
        // Self-targeting: a session renames *itself* (resolved from its UUID),
        // so no `old` is needed and any session — including can_orchestrate=false
        // workers — can name itself.
        if (!s) return { ok: false, error: "unknown session" }
        const res = resolveSelfRename(stringArg(op.args, "name"), s.name, this.registry.list().map((x) => x.name), !!s.self_renamed)
        if (!res.ok) return { ok: false, error: res.error }
        const oldName = s.name
        if (res.name !== oldName) {
          this.registry.rename(s.id, res.name)
          this.registry.markSelfRenamed(s.id)
          await this.ports.orchestration.refreshTelegramMenu()
          this.ports.getWebChannel()?.broadcastToAll({ type: "session_renamed", id: s.id, old: oldName, new: res.name })
          // Spec §9.5: the workspace name follows its primary session, and an
          // AGENT renaming itself through this tool is the main way that
          // happens — the web renameSession opt above is the rarer path. Both
          // frames go out; an old client only knows the first one.
          const wsId = propagateSessionRename(this.registry.workspaces, s.id, res.name)
          if (wsId) {
            const dto = this.ports.orchestration.wsDto(wsId)
            if (dto) this.ports.getWebChannel()?.broadcastToAll({ type: "workspace_changed", workspace: dto })
          }
        }
        return { ok: true, value: { name: res.name } }
      }
      case "mute_session": {
        const name = stringArg(op.args, "name")
        const mutedValue = optionalBooleanArg(op.args, "muted")
        if (mutedValue === undefined) return { ok: false, error: "muted must be a boolean" }
        const muted = this.registry.resolveName(name)
        if (!muted) return { ok: false, error: `no such session: ${name}` }
        this.registry.setMuted(muted.id, mutedValue)
        this.ports.getWebChannel()?.broadcastToAll({ type: "session_state", session: muted.id, mute: mutedValue })
        return { ok: true, value: "ok" }
      }
      case "list_sessions":  { return { ok: true, value: this.registry.listVisible().map((s: any) => ({ name: s.name, workdir: s.workdir, mute: s.mute })) } }
      case "set_active":     { const t = this.registry.resolveName(stringArg(op.args, "name")); if (!t) return { ok: false, error: "no such session" }; this.registry.setActive(stringArg(op.args, "chat_id"), t.id); return { ok: true, value: "ok" } }
      case "get_active":     { return { ok: true, value: this.registry.getActive(stringArg(op.args, "chat_id")) } }
      case "memory_search": {
        const q = stringArg(op.args, "query")
        const limit = typeof op.args?.limit === "number" ? op.args.limit : 10
        const includePersonal = s?.role === "personal_assistant"
        return { ok: true, value: this.ports.stores.searchStore.searchKnowledge(q, { includePersonal, limit }) }
      }
      case "find_sessions": {
        const q = stringArg(op.args, "query")
        const limit = typeof op.args?.limit === "number" ? op.args.limit : 10
        return { ok: true, value: this.ports.stores.searchStore.searchSessions(q, {
          project: typeof op.args?.project === "string" ? op.args.project : undefined,
          since: typeof op.args?.since === "string" ? op.args.since : undefined,
          agent: typeof op.args?.agent === "string" ? op.args.agent : undefined,
          limit,
        }) }
      }
      case "read_session": {
        const id = stringArg(op.args, "session_id")
        const row = this.ports.stores.db.query("SELECT workdir, agent, agent_session_id FROM sessions WHERE id = ? AND internal = 0").get(id) as { workdir: string; agent: string; agent_session_id: string | null } | null
        if (!row) return { ok: false, error: "no such session" }
        if (row.agent !== "claude" || !row.agent_session_id) {
          return { ok: true, value: { transcript: false, note: "no JSONL transcript for this agent; use the broker message history", messages: this.ports.stores.messageLog.get(id, 200) } }
        }
        const includeToolCalls = op.args?.include_tool_calls !== false
        const grep = typeof op.args?.grep === "string" ? op.args.grep : undefined
        const text = renderTranscript(claudeTranscriptPath(row.workdir, row.agent_session_id), { includeToolCalls, grep })
        return { ok: true, value: { transcript: true, session_id: id, text } }
      }
      case "expose_port": {
        if (!s) return { ok: false, error: "unknown session" }
        const port = optionalNumberArg(op.args, "port")
        if (!port || port < 1 || port > 65535) return { ok: false, error: "port must be 1-65535" }
        let domain = optionalStringArg(op.args, "domain")
        if (!domain) {
          domain = "px-" + randomBytes(4).toString("hex")
        }
        try {
          const isPublic = optionalBooleanArg(op.args, "public") === true
          const entry = this.registry.addProxy({ domain, sessionId: s.id, port, isPublic })
          const url = buildProxyPublicUrl(entry.domain, {
            baseDomain: process.env.MUX_PROXY_BASE_DOMAIN,
            publicUrl: this.ports.orchestration.exposedProxyLinksBaseUrl(),
          })
          this.ports.getWebChannel()?.broadcastToAll({ type: "proxy_created", proxy: this.ports.orchestration.proxyWsPayload(entry, this.ports.orchestration.proxyLiveness.getStatus(entry.domain)) })
          void this.ports.orchestration.proxyLiveness.refresh()
          return { ok: true, value: { url, domain: entry.domain, port: entry.port, isPublic: entry.isPublic } }
        } catch (err: any) {
          return { ok: false, error: err?.message ?? String(err) }
        }
      }
      case "unexpose_port": {
        if (!s) return { ok: false, error: "unknown session" }
        const domain = stringArg(op.args, "domain")
        if (!domain) return { ok: false, error: "domain required" }
        const existing = this.registry.getProxy(domain)
        if (!existing) return { ok: false, error: `no proxy registered for domain "${domain}"` }
        if (existing.sessionName !== s.name) return { ok: false, error: "can only remove your own proxies" }
        this.registry.removeProxy(domain)
        this.ports.getWebChannel()?.broadcastToAll({ type: "proxy_removed", domain })
        return { ok: true, value: { removed: true } }
      }
      case "set_proxy_public": {
        if (!s) return { ok: false, error: "unknown session" }
        const domain = stringArg(op.args, "domain")
        if (!domain) return { ok: false, error: "domain required" }
        const publicValue = optionalBooleanArg(op.args, "public")
        if (publicValue === undefined) return { ok: false, error: "public (boolean) required" }
        const existing = this.registry.getProxy(domain)
        if (!existing) return { ok: false, error: `no proxy registered for domain "${domain}"` }
        if (existing.sessionName !== s.name) return { ok: false, error: "can only update your own proxies" }
        try {
          const entry = this.registry.setProxyPublic(domain, publicValue)
          this.ports.getWebChannel()?.broadcastToAll({ type: "proxy_updated", proxy: this.ports.orchestration.proxyWsPayload(entry, this.ports.orchestration.proxyLiveness.getStatus(entry.domain)) })
          return { ok: true, value: { domain: entry.domain, isPublic: entry.isPublic } }
        } catch (err: any) {
          return { ok: false, error: err?.message ?? String(err) }
        }
      }
      case "list_devices": {
        return { ok: true, value: await listDevices() }
      }
      case "start_display": {
        if (!s) return { ok: false, error: "unknown session" }
        try {
          const info = await this.ports.displays.start({
            sessionDisplayName: s.name,
            provider: optionalProviderArg(op.args, "provider"),
            device: optionalStringArg(op.args, "device"),
            width: optionalNumberArg(op.args, "width"),
            height: optionalNumberArg(op.args, "height"),
          })
          const hint = info.provider === "linux-xvfb" ? `run apps with DISPLAY=${info.display}`
            : info.provider === "scrcpy" ? `streaming device ${info.display} via scrcpy`
            : "streaming the macOS real screen"
          return { ok: true, value: { id: info.id, provider: info.provider, display: info.display, hint } }
        } catch (err: any) {
          return { ok: false, error: err?.message ?? String(err) }
        }
      }
      case "stop_display": {
        if (!s) return { ok: false, error: "unknown session" }
        const id = stringArg(op.args, "id")
        if (!id) return { ok: false, error: "id required" }
        const existing = this.ports.displays.get(id)
        if (!existing) return { ok: false, error: `no display stream "${id}"` }
        if (existing.sessionName !== s.name) return { ok: false, error: "can only stop your own display streams" }
        await this.ports.displays.stop(id)
        return { ok: true, value: { stopped: true } }
      }
      case "rpc_resolve": { this.ports.getAgentRpc().settle(String(op.args.request_id), op.args.data); return { ok: true, value: "ok" } }
      case "rpc_reject":  { this.ports.getAgentRpc().fail(String(op.args.request_id), String(op.args.error ?? "rejected")); return { ok: true, value: "ok" } }
    }
    return { ok: false, error: "unknown orchestration op" }
    } catch (err) {
      return { ok: false, error: String(err instanceof Error ? err.message : err) }
    }
  }

  /** Register a freshly spawned adapter under its session and wire its events.
   *  One implementation of the instanceof dispatch that main.ts used to
   *  duplicate 3× verbatim (and the supervisor silently lacked — the
   *  half-filled-bag PA bug). The name→id fallback is preserved as-is. */
  registerSpawnedAdapter(name: string, adapter: AgentAdapter, handle?: unknown): void {
    const session = this.registry.resolveName(name)
    const sid = session?.id ?? name
    if (adapter instanceof CodexAdapter) {
      this.registerCodexRuntime(sid, name, adapter, handle as CodexSpawnHandle)
    } else if (adapter instanceof CursorAdapter) {
      this.registerCursorRuntime(sid, adapter)
    } else if (adapter instanceof OpenCodeAdapter) {
      this.registerOpenCodeRuntime(sid, name, adapter, handle as OpenCodeSpawnHandle)
    } else if (adapter instanceof GrokAdapter) {
      this.registerGrokRuntime(sid, adapter)
    }
    this.ports.resume.wireAdapterEvents(adapter, sid)
  }

  // ── applyConfig: one entry for model/effort changes (the last kind ladder) ─
  //
  // The per-kind DIALECT (what a model/effort change does to a live session)
  // lives in each agents/<kind>/session.ts applyConfig. This component owns
  // the STATE half: the registry writes, the pendingReapply queue (claude's
  // queue-until-idle rule), and the runtime swap for restart-style kinds.

  /** The one entry for a session model/effort change. Result shape matches the
   *  old main.ts switchSessionModel/switchSessionReasoningLevel contract. */
  async applyConfig(
    sessionId: string,
    change: { model?: string; effort?: string; applyNow?: boolean },
  ): Promise<{ ok: true; status: "applied" | "queued" } | { ok: false; error: string }> {
    if (change.model !== undefined) {
      const r = await this.switchModel(sessionId, change.model, change.applyNow)
      if (!r.ok || change.effort === undefined) return r
    }
    if (change.effort !== undefined) {
      return this.switchReasoningLevel(sessionId, change.effort, change.applyNow)
    }
    return { ok: true, status: "applied" }
  }

  private async switchModel(
    sessionId: string,
    newModel: string,
    applyNow?: boolean,
  ): Promise<{ ok: true; status: "applied" | "queued" } | { ok: false; error: string }> {
    const session = this.registry.get(sessionId)
    if (!session) return { ok: false, error: `no such session: ${sessionId}` }

    const oldModel = session.model
    const oldReasoningLevel = session.reasoningLevel
    this.registry.setModel(sessionId, newModel)
    if (session.reasoningLevel) {
      const clamped = clampSessionReasoningLevel({ ...session, model: newModel }, newModel, this.ports.config.lookupModels)
      if (clamped !== session.reasoningLevel) {
        this.registry.setReasoningLevel(sessionId, clamped)
      }
    }

    // cursor, opencode and grok read their adapter's `model` field fresh on each
    // turn (opencode re-parses it in send() via parseModel(); grok folds it into
    // each session/prompt), so a model switch is a live in-process update —
    // no process/serve restart, no config reapply. The typed set is each kind's
    // applyConfig dialect; `changed` masks out the effort half.
    if (session.agent === AgentKind.Cursor || session.agent === AgentKind.OpenCode || session.agent === AgentKind.Grok) {
      const adapter = this.runtimes.get(session.id)?.adapter
      if (adapter) {
        await agents[session.agent].applyConfig(
          { ...this.resumeCtx(session.id), adapter },
          session, session.name,
          { model: newModel, changed: { model: true, effort: false } },
        )
      }
      this.ports.getWebChannel()?.broadcastToAll({ type: "session_state", session: session.id, model: newModel })
      return { ok: true, status: "applied" }
    }

    // Claude switches are typed into the TUI, which is only safe on an idle
    // composer — force queue-until-idle (user decision: never type mid-turn).
    const effectiveApplyNow = session.agent === AgentKind.Claude ? false : applyNow ?? false
    return this.applyOrDeferReapply(sessionId, { oldModel, oldReasoningLevel }, effectiveApplyNow)
  }

  private async switchReasoningLevel(
    sessionId: string,
    newLevel: string,
    applyNow?: boolean,
  ): Promise<{ ok: true; status: "applied" | "queued" } | { ok: false; error: string }> {
    const session = this.registry.get(sessionId)
    if (!session) return { ok: false, error: `no such session: ${sessionId}` }

    if (session.agent === AgentKind.Cursor) {
      return { ok: false, error: "cursor sessions use model selection for reasoning depth" }
    }

    const models = this.ports.config.lookupModels(session.agent)
    const levels = supportedReasoningLevels(session.agent, models, session.model)
    if (!levels.some((l) => l.id === newLevel)) {
      return { ok: false, error: `unsupported reasoning level: ${newLevel}` }
    }

    const oldReasoningLevel = session.reasoningLevel
    this.registry.setReasoningLevel(sessionId, newLevel)

    // Same queue-until-idle rule as switchModel for claude (typed /effort).
    const effectiveApplyNow = session.agent === AgentKind.Claude ? false : applyNow ?? false
    return this.applyOrDeferReapply(sessionId, { oldModel: session.model, oldReasoningLevel }, effectiveApplyNow)
  }

  // Apply a model/effort change now if the session is idle (or applyNow), else
  // record a deferred respawn to run when the turn ends. The registry was already
  // updated by the caller; `olds` are restored only if a (now or deferred) apply fails.
  private async applyOrDeferReapply(
    sessionId: string,
    olds: PreChangeConfig,
    applyNow: boolean,
  ): Promise<{ ok: true; status: "applied" | "queued" } | { ok: false; error: string }> {
    const phase = this.ports.agentState.get(sessionId).phase
    if (shouldDeferReapply(phase, applyNow)) {
      this.pendingReapply.mark(sessionId, olds)
      return { ok: true, status: "queued" }
    }
    const current = this.registry.get(sessionId)
    const result = await this.reapplyAgentConfig(sessionId, current ? changedSince(olds, current) : undefined)
    if (!result.ok) {
      this.registry.setModel(sessionId, olds.oldModel)
      this.registry.setReasoningLevel(sessionId, olds.oldReasoningLevel)
      return result
    }
    return { ok: true, status: "applied" }
  }

  /** Drain hook: called on every agent-state change (main.ts listener). When a
   *  session settles to idle with a deferred apply owed, run it; a failure
   *  rolls the registry back, tells the clients, and notifies the user. The
   *  returned promise is for tests — production fires and forgets. */
  drainPendingReapply(sessionId: string, phase: AgentPhase): Promise<void> {
    if (phase !== "idle" || !this.pendingReapply.has(sessionId)) return Promise.resolve()
    const olds = this.pendingReapply.take(sessionId)!
    const drainSession = this.registry.get(sessionId)
    return this.reapplyAgentConfig(sessionId, drainSession ? changedSince(olds, drainSession) : undefined).then((r) => {
      if (!r.ok) {
        this.registry.setModel(sessionId, olds.oldModel)
        this.registry.setReasoningLevel(sessionId, olds.oldReasoningLevel)
        const s = this.registry.get(sessionId)
        this.ports.getWebChannel()?.broadcastToAll({ type: "session_state", session: sessionId, model: s?.model, reasoningLevel: this.ports.resume.sessionEffort(s ?? {}) })
        void this.ports.register.notifyAgentError(sessionId, s?.name ?? sessionId, "config", `Failed to apply model/effort change: ${r.error}`)
      }
    }).catch((err) => log.warn("drain_reapply_failed", { sessionId, err: String(err) }))
  }

  /** Re-apply the session's stored model/effort to its LIVE runtime — the
   *  per-kind dispatch. `changed` narrows to what the user actually touched. */
  private async reapplyAgentConfig(
    sessionId: string,
    changed?: { model: boolean; effort: boolean },
  ): Promise<{ ok: true } | { ok: false; error: string }> {
    const session = this.registry.get(sessionId)
    if (!session) return { ok: false, error: `no such session: ${sessionId}` }

    const effort = this.ports.resume.sessionEffort(session)

    if (session.agent === AgentKind.Claude) {
      // Live switch: type /model and/or /effort into the running TUI — never a
      // kill+respawn (user decision 2026-07-10). Failure is an explicit error;
      // callers roll the registry back.
      const wid = await this.ports.backend.runtimeTargetIdOf(session)
      if (!wid) return { ok: false, error: "session window not found" }
      const result = await agents.claude.applyConfig(
        { ...this.resumeCtx(session.id), windowId: wid, backend: this.ports.resume.sessionBackend },
        session, session.name,
        { model: session.model, effort, changed },
      )
      if (!result.ok) return result
      this.ports.getWebChannel()?.broadcastToAll({
        type: "session_state",
        session: session.id,
        model: session.model,
        reasoningLevel: effort,
      })
      return { ok: true }
    }

    if (session.agent === AgentKind.Codex) {
      // Codex has no live setter: full kill + respawn of the app-server with
      // the new flags. The dialect builds the fresh runtime; the swap and
      // rewire happen HERE so callers never hold a half-dead adapter.
      try {
        const runtime = this.runtimes.get(session.id)
        if (runtime?.kind === AgentKind.Codex) runtime.handle.kill()
        this.deleteRuntime(session.id)

        if (!session.agent_home) return { ok: false, error: "codex session missing agent_home" }

        const result = await agents.codex.applyConfig(
          this.resumeCtx(session.id),
          { ...session, agent_home: session.agent_home },
          session.name,
          { model: session.model, effort, changed },
        )
        this.registerCodexRuntime(session.id, session.name, result.runtime.adapter, result.runtime.handle)
        this.ports.resume.wireAdapterEvents(result.runtime.adapter, session.id)

        this.ports.getWebChannel()?.broadcastToAll({
          type: "session_state",
          session: session.id,
          model: session.model,
          reasoningLevel: effort,
        })
        return { ok: true }
      } catch (err: any) {
        return { ok: false, error: `agent config apply failed: ${err?.message ?? String(err)}` }
      }
    }

    if (session.agent === AgentKind.Grok) {
      try {
        const runtime = this.runtimes.get(session.id)
        if (runtime?.kind !== AgentKind.Grok) return { ok: false, error: "grok session has no live adapter" }
        const result = await agents.grok.applyConfig(
          { ...this.resumeCtx(session.id), adapter: runtime.adapter },
          session, session.name,
          { model: session.model, effort, changed },
        )
        if (!result.ok) return result
        this.ports.getWebChannel()?.broadcastToAll({
          type: "session_state",
          session: session.id,
          model: session.model,
          reasoningLevel: effort,
        })
        return { ok: true }
      } catch (err: any) {
        return { ok: false, error: `agent config apply failed: ${err?.message ?? String(err)}` }
      }
    }

    return { ok: false, error: `agent does not support reasoning level: ${session.agent}` }
  }

  // ── Resume: one flow, three sources, five kinds (Move 3) ──────────────────
  //
  // The per-kind ARMS are shared by every source. The per-source FRAMES keep
  // their behavior (logging, activate-vs-resume, broadcast) from the three old
  // main.ts ladders. Fixed here by structure: preambles are rewritten on EVERY
  // resume; opencode/grok have suspended arms; the cursor arm passes pluginArgs
  // from every source (boot silently dropped them before).

  // Genuinely claude-only: called from the claude resume arms, where the shim
  // connect IS the readiness signal. Kind-agnostic waits use waitDeliverable.
  private async waitForConnected(sessionId: string, timeoutMs: number): Promise<boolean> {
    const deadline = Date.now() + timeoutMs
    while (Date.now() < deadline) {
      if (this.registry.get(sessionId)?.connected) return true
      await new Promise<void>(r => setTimeout(r, 100))
    }
    log.warn("wait_session_connected_timeout", { sessionId, timeoutMs })
    return false
  }

  private async resumeCodexArm(session: ResumeRow, name: string): Promise<CodexSpawnHandle> {
    const { adapter, handle } = await agents.codex.resume(this.resumeCtx(session.id), session, name)
    this.registerCodexRuntime(session.id, name, adapter, handle)
    this.ports.resume.wireAdapterEvents(adapter, session.id)
    return handle
  }

  private async resumeCursorArm(session: ResumeRow, name: string): Promise<void> {
    const { adapter } = await agents.cursor.resume(this.resumeCtx(session.id), session, name)
    this.registerCursorRuntime(session.id, adapter)
    this.ports.resume.wireAdapterEvents(adapter, session.id)
  }

  private async resumeOpenCodeArm(session: ResumeRow, name: string): Promise<OpenCodeSpawnHandle> {
    const { adapter, handle } = await agents.opencode.resume(this.resumeCtx(session.id), session, name)
    this.registerOpenCodeRuntime(session.id, name, adapter, handle)
    this.ports.resume.wireAdapterEvents(adapter, session.id)
    return handle
  }

  /** The dialect's narrow view of this component (see agents/session-types.ts):
   *  effort resolution, attachment resolution, and agent-session-id persistence
   *  for ONE session. Registration + event wiring stay out — that's the state
   *  half, done by the arms after the dialect returns. */
  private resumeCtx(sessionId: string): ResumeCtx {
    return {
      sessionEffort: (s) => this.ports.resume.sessionEffort(s),
      resolveAttachment: this.ports.resume.resolveAttachment,
      persistAgentSessionId: (sid) => { this.registry.sessions.setAgentSessionId(sessionId, sid) },
    }
  }

  private async resumeGrokArm(session: ResumeRow, name: string): Promise<void> {
    const { adapter } = await agents.grok.resume(this.resumeCtx(session.id), session, name)
    this.registerGrokRuntime(session.id, adapter)
    this.ports.resume.wireAdapterEvents(adapter, session.id)
  }

  /** Suspended → live (lazy, triggered by the next inbound message). */
  async resumeSuspended(session: { id: string; name: string; agent: string; workdir: string; model?: string; reasoningLevel?: string; pid?: number; agent_session_id?: string; agent_home?: string; tmux_window_id?: string | null; repo_root?: string | null; session_branch?: string | null; base_branch?: string | null }): Promise<boolean> {
    const { sessionBackend, tmuxSession } = this.ports.resume
    let resumedRuntimePid: number | null = null
    try {
      log.info("resume_suspended_begin", {
        name: session.name,
        id: session.id,
        agent: session.agent,
        status: this.registry.get(session.id)?.status,
        has_agent_session_id: !!session.agent_session_id,
      })
      await this.ports.resume.ensureSessionWorktree(session)
      if (session.agent === "claude") {
        await this.ports.resume.bind(session.id)
        preAcceptTrust(session.workdir)
        // Clear ONLY our own prior window, by id — never kill by name (a name
        // can be shared with a sibling's live window).
        if (session.tmux_window_id) await sessionBackend.kill(session.tmux_window_id).catch(() => {})
        const windowName = ensureUnique(session.name, new Set((await sessionBackend.list(tmuxSession)).map(target => target.name)))
        log.info("resume_suspended_claude", { name: session.name, window: windowName })
        const effort = this.ports.resume.sessionEffort(session)
        const spec = buildClaudeSpawnSpec({
          name: session.name, model: session.model, effort, sessionId: session.id,
          claudeSessionId: session.agent_session_id, resume: !!session.agent_session_id,
          workdir: session.workdir,
        })
        const target = await sessionBackend.create({ group: tmuxSession, name: windowName, cwd: session.workdir, ...spec, cols: 80, rows: 24 })
        this.registry.sessions.setTmuxWindowId(session.id, target.id)
        resumedRuntimePid = target.pid
        await sendChannelConsentEnter(target.id, { backend: sessionBackend })
        await this.waitForConnected(session.id, 25_000)
      } else if (session.agent === "codex" && session.agent_session_id && session.agent_home) {
        await this.ports.resume.bind(session.id)
        await this.resumeCodexArm({ ...session, agent_home: session.agent_home }, session.name)
      } else if (session.agent === "cursor" && session.agent_home) {
        await this.resumeCursorArm({ ...session, agent_home: session.agent_home }, session.name)
      } else if (session.agent === "opencode" && session.agent_home) {
        await this.ports.resume.bind(session.id)
        await this.resumeOpenCodeArm({ ...session, agent_home: session.agent_home }, session.name)
      } else if (session.agent === AgentKind.Grok && session.agent_home) {
        await this.ports.resume.bind(session.id)
        await this.resumeGrokArm({ ...session, agent_home: session.agent_home }, session.name)
      } else {
        log.warn("resume_suspended_no_path", { name: session.name, agent: session.agent })
        return false
      }
      this.registry.sessions.activate(session.id, resumedSessionPid(resumedRuntimePid, session.pid))
      return true
    } catch (err: any) {
      log.error("resume_suspended_failed", { name: session.name, err: String(err) })
      return false
    }
  }

  /** Archived → live. */
  async resumeFromArchive(sessionId: string): Promise<{ ok: boolean; name?: string; error?: string }> {
    const { sessionBackend, tmuxSession } = this.ports.resume
    const session = this.registry.sessions.getById(sessionId) // bypasses archived filter in registry.get()
    if (!session || session.status !== "archived") {
      return { ok: false, error: "Session not found or not archived" }
    }

    let name = session.name
    const takenRuntimeNames = session.agent === AgentKind.Claude
      ? new Set((await sessionBackend.list(tmuxSession)).map(target => target.name))
      : new Set<string>()
    const takenNames = new Set([...this.registry.takenNames(), ...takenRuntimeNames])
    if (takenNames.has(name)) {
      name = ensureUnique(name, takenNames)
    }

    let resumedRuntimeTargetId: string | undefined
    let resumedRuntimePid: number | null = null
    try {
      await this.ports.resume.ensureSessionWorktree(session)
      if (session.agent === "claude") {
        await this.ports.resume.bind(sessionId)
        const effort = this.ports.resume.sessionEffort(session)
        const spec = buildClaudeSpawnSpec({
          name, model: session.model, effort, sessionId,
          claudeSessionId: session.agent_session_id, resume: !!session.agent_session_id,
          workdir: session.workdir,
        })
        const target = await sessionBackend.create({ group: tmuxSession, name, cwd: session.workdir, ...spec, cols: 80, rows: 24 })
        resumedRuntimeTargetId = target.id
        resumedRuntimePid = target.pid
        void sendChannelConsentEnter(target.id, { backend: sessionBackend })
      } else if (session.agent === "codex" && session.agent_session_id && session.agent_home) {
        await this.ports.resume.bind(sessionId)
        await this.resumeCodexArm({ ...session, agent_home: session.agent_home }, name)
      } else if (session.agent === "cursor" && session.agent_home) {
        await this.resumeCursorArm({ ...session, agent_home: session.agent_home }, name)
      } else if (session.agent === "opencode" && session.agent_home) {
        await this.ports.resume.bind(sessionId)
        await this.resumeOpenCodeArm({ ...session, agent_home: session.agent_home }, name)
      } else if (session.agent === AgentKind.Grok && session.agent_home) {
        await this.ports.resume.bind(sessionId)
        await this.resumeGrokArm({ ...session, agent_home: session.agent_home }, name)
      } else {
        return { ok: false, error: `Cannot resume agent type: ${session.agent}` }
      }

      const resumed = this.registry.sessions.resume(sessionId, name, resumedRuntimePid ?? process.pid)
      if (resumedRuntimeTargetId) this.registry.sessions.setTmuxWindowId(sessionId, resumedRuntimeTargetId)

      // Use the post-resume row (in_progress + top sort_order), not the stale archived snapshot.
      const live = resumed ?? this.registry.sessions.getById(sessionId) ?? session
      this.ports.getWebChannel()?.broadcastToAll({
        type: "session_added",
        session: {
          id: sessionId,
          name: live.name,
          workdir: live.workdir,
          agent: live.agent,
          status: "active",
          repo_root: live.repo_root || undefined,
          session_branch: live.session_branch || undefined,
          finish_job: live.finish_job,
          user_status: live.user_status,
          sort_order: live.sort_order,
          draft_payload: live.draft_payload,
        },
      })

      await this.ports.orchestration.refreshTelegramMenu()
      return { ok: true, name }
    } catch (err: any) {
      log.error("resume_from_archive_failed", { name: session.name, err: String(err) })
      return { ok: false, error: String(err?.message ?? err) }
    }
  }

  /** Broker boot: rebuild the in-process adapters for non-claude sessions.
   *  (Claude sessions are reconciled by the supervisor: surviving panes
   *  reattach via the shim; dead ones suspend.) Failures log and continue. */
  async resumeAtBoot(): Promise<void> {
    for (const s of this.registry.list()) {
      if (s.agent === "codex") {
        if (!s.agent_session_id || !s.agent_home) {
          log.warn("codex_resume_skip", { name: s.name, reason: "missing agent_session_id or agent_home" })
          continue
        }
        try {
          const handle = await this.resumeCodexArm({ ...s, agent_home: s.agent_home }, s.name)
          if (s.status === "suspended") this.registry.sessions.activate(s.id, handle.pid ?? process.pid)
          log.info("codex_resume_ok", { name: s.name, thread: s.agent_session_id })
        } catch (err: any) {
          log.warn("codex_resume_failed", { name: s.name, err: String(err) })
        }
      } else if (s.agent === "cursor") {
        // Cursor sessions are per-turn — no persistent process. The adapter
        // just needs agent_home (config + auth dir). agent_session_id may be
        // absent if the session never received a first message yet; that's OK —
        // initialSessionId=undefined means the first turn starts fresh.
        if (!s.agent_home) {
          log.warn("cursor_resume_skip", { name: s.name, reason: "missing agent_home" })
          continue
        }
        try {
          await this.resumeCursorArm({ ...s, agent_home: s.agent_home }, s.name)
          if (s.status === "suspended") this.registry.sessions.activate(s.id, process.pid)
          log.info("cursor_resume_ready", { name: s.name, session_id: s.agent_session_id ?? "(first turn pending)" })
        } catch (err: any) {
          log.warn("cursor_resume_failed", { name: s.name, err: String(err) })
        }
      } else if (s.agent === "opencode") {
        // opencode's worker (in-process adapter + broker-child `opencode serve`)
        // dies with the broker; without this respawn the row survives but the
        // runtime is empty → inbound hits adapter_not_ready and never replies.
        if (!s.agent_home) {
          log.warn("opencode_resume_skip", { name: s.name, reason: "missing agent_home" })
          continue
        }
        try {
          const handle = await this.resumeOpenCodeArm({ ...s, agent_home: s.agent_home }, s.name)
          if (s.status === "suspended") this.registry.sessions.activate(s.id, handle.pid ?? process.pid)
          log.info("opencode_resume_ok", { name: s.name, session_id: s.agent_session_id ?? "(fresh)" })
        } catch (err: any) {
          log.warn("opencode_resume_failed", { name: s.name, err: String(err) })
        }
      } else if (s.agent === AgentKind.Grok) {
        // grok's worker (in-process adapter + `grok agent stdio` child) dies with
        // the broker; same rationale as opencode. agent_home holds the private
        // ~/.grok (config + credential path) the child needs.
        if (!s.agent_home) {
          log.warn("grok_resume_skip", { name: s.name, reason: "missing agent_home" })
          continue
        }
        try {
          await this.resumeGrokArm({ ...s, agent_home: s.agent_home }, s.name)
          if (s.status === "suspended") this.registry.sessions.activate(s.id, process.pid)
          log.info("grok_resume_ok", { name: s.name, session_id: s.agent_session_id ?? "(fresh)" })
        } catch (err: any) {
          log.warn("grok_resume_failed", { name: s.name, err: String(err) })
        }
      }
    }
  }
}

// ---- socket-op argument parsing (moved with the handlers from main.ts) ----

function stringArg(args: Record<string, unknown>, key: string): string {
  const value = args[key]
  if (typeof value !== "string") throw new Error(`${key} must be a string`)
  return value
}

function optionalStringArg(args: Record<string, unknown>, key: string): string | undefined {
  const value = args[key]
  if (value === undefined) return undefined
  if (typeof value !== "string") throw new Error(`${key} must be a string`)
  return value
}

function optionalStringArrayArg(args: Record<string, unknown>, key: string): string[] | undefined {
  const value = args[key]
  if (value === undefined) return undefined
  if (!Array.isArray(value) || !value.every((item) => typeof item === "string")) {
    throw new Error(`${key} must be an array of strings`)
  }
  return value
}

function optionalFormatArg(args: Record<string, unknown>, key: string): "text" | "markdownv2" | undefined {
  const value = args[key]
  if (value === undefined) return undefined
  if (value === "text" || value === "markdownv2") return value
  throw new Error(`${key} must be text or markdownv2`)
}

function optionalNumberArg(args: Record<string, unknown>, key: string): number | undefined {
  const value = args[key]
  if (value === undefined) return undefined
  if (typeof value !== "number") throw new Error(`${key} must be a number`)
  return value
}

function optionalBooleanArg(args: Record<string, unknown>, key: string): boolean | undefined {
  const value = args[key]
  if (value === undefined) return undefined
  if (typeof value !== "boolean") throw new Error(`${key} must be a boolean`)
  return value
}

function optionalProviderArg(args: Record<string, unknown>, key: string): ProviderName | undefined {
  const value = optionalStringArg(args, key)
  if (value === undefined || value === "linux-xvfb" || value === "macos-screen" || value === "scrcpy") return value
  throw new Error(`${key} must be a known display provider`)
}
