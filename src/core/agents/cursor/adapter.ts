import { EventEmitter } from "events"
import type { AgentAdapter, AgentKind, InboundMeta } from "../types"
import { parseCursorStream } from "./stream-parser"
import { makeLogger } from "../../../shared/log"

const log = makeLogger("agents/cursor/adapter")

export type CursorRunner = (
  args: string[],
  onLine: (line: string) => void,
  onExit: (code: number | null) => void,
  /** Aborts the in-flight turn (the real runner SIGTERMs the cursor-agent child). */
  signal?: AbortSignal,
) => Promise<void>

export type CursorAdapterOpts = {
  sessionName: string
  workdir: string
  runner: CursorRunner
  persistSessionId: (id: string) => Promise<void>
  initialSessionId?: string
  model?: string
  /** supermux plugin-host flags (`--plugin-dir <dir>` pairs), spliced into every turn. */
  pluginArgs?: string[]
  /** Resolve an inbound attachment file_id to a local path on disk. cursor-agent
   * has no image/attachment input flag (only the `-p` prompt), so we fold the
   * resolved path into the prompt and let cursor open it with its own tools.
   * Without this, cursor sessions silently drop attachments. */
  resolveAttachment?: (file_id: string) => Promise<string>
}

export class CursorAdapter extends EventEmitter implements AgentAdapter {
  readonly kind: AgentKind = "cursor"
  readonly sessionName: string
  readonly workdir: string

  private runner: CursorRunner
  private persistSessionId: (id: string) => Promise<void>
  private sessionId?: string
  private _model?: string

  // Every send() enqueues; a single drain loop processes the queue. The
  // `draining` flag prevents two drains from running in parallel. This
  // pattern eliminates the race window where `this.active` was briefly
  // undefined between successive runOne calls.
  private queue: { text: string; attachmentFileId?: string; attachmentName?: string; resolve: () => void; reject: (e: Error) => void }[] = []
  private draining = false
  // AbortController for the turn currently running (set in runOne, cleared on
  // exit). interrupt() aborts it → the runner SIGTERMs cursor-agent.
  private activeAbort?: AbortController
  private pluginArgs: string[]
  private resolveAttachment?: (file_id: string) => Promise<string>

  constructor(opts: CursorAdapterOpts) {
    super()
    this.sessionName = opts.sessionName
    this.workdir = opts.workdir
    this.runner = opts.runner
    this.persistSessionId = opts.persistSessionId
    this.sessionId = opts.initialSessionId
    this._model = opts.model
    this.pluginArgs = opts.pluginArgs ?? []
    this.resolveAttachment = opts.resolveAttachment
  }

  set model(m: string | undefined) { this._model = m }
  get model(): string | undefined { return this._model }

  async start(): Promise<void> { /* no persistent process */ }
  async resume(): Promise<void> { /* sessionId already set in ctor; next send resumes */ }
  async stop(): Promise<void> { /* no persistent process */ }

  async send(text: string, meta?: InboundMeta): Promise<void> {
    return new Promise<void>((resolve, reject) => {
      // Carry attachment meta through the queue and resolve it in the drain
      // loop (not here) so concurrent sends keep their enqueue order.
      this.queue.push({ text, attachmentFileId: meta?.attachment_file_id, attachmentName: meta?.attachment_name, resolve, reject })
      if (!this.draining) void this.drain()
    })
  }

  private async drain(): Promise<void> {
    if (this.draining) return
    this.draining = true
    try {
      while (this.queue.length) {
        const next = this.queue.shift()!
        try {
          const text = await this.withAttachment(next.text, next.attachmentFileId, next.attachmentName)
          await this.runOne(text)
          next.resolve()
        } catch (err: any) {
          next.reject(err instanceof Error ? err : new Error(String(err)))
        }
      }
    } finally {
      this.draining = false
    }
  }

  /** Fold a resolved attachment path into the prompt. cursor-agent has no
   * image input flag, so this is the only way to surface uploads; cursor can
   * then open the file (image or otherwise) with its own filesystem tools. On
   * resolution failure we fall back to the original prompt rather than fail. */
  private async withAttachment(text: string, fileId?: string, name?: string): Promise<string> {
    if (!fileId || !this.resolveAttachment) return text
    try {
      const path = await this.resolveAttachment(fileId)
      const label = name ? `${name} (${path})` : path
      return text ? `${text}\n\n[Attached file: ${label}]` : `[Attached file: ${label}]`
    } catch (err: any) {
      log.warn("cursor_attachment_resolve_failed", { session: this.sessionName, file_id: fileId, err: err?.message ?? String(err) })
      return text
    }
  }

  private async runOne(text: string): Promise<void> {
    const args = [
      "-p", text,
      "--output-format", "stream-json",
      "--stream-partial-output",
      "--approve-mcps",
      "--force",
      "--workspace", this.workdir,
      ...this.pluginArgs,
      ...(this._model ? ["--model", this._model] : []),
      ...(this.sessionId ? ["--resume", this.sessionId] : []),
    ]
    // Wait for BOTH the runner promise AND the exit callback. The runner's
    // promise resolves when the subprocess returns control; the exit
    // callback fires shortly after. Awaiting both keeps the queue's drain
    // loop honest about when a turn is fully done.
    let exitResolve!: () => void
    const exitDone = new Promise<void>((r) => { exitResolve = r })
    let turnStarted = false
    const abort = new AbortController()
    this.activeAbort = abort
    try {
      await this.runner(
        args,
        (line) => {
          if (!turnStarted) { turnStarted = true; this.emit("turn-start", { kind: "turn-start" }) }
          this.dispatchLine(line)
        },
        (_code) => {
          this.flushAssistant()
          this.emit("turn-complete", { kind: "turn-complete" })
          exitResolve()
        },
        abort.signal,
      )
      await exitDone
    } finally {
      this.activeAbort = undefined
    }
  }

  // With --stream-partial-output, cursor-agent sends multiple `assistant`
  // events: each is a cumulative snapshot of the text so far ("Hello" →
  // "Hello." → "Hello. How are you?"). Emitting each one as a separate
  // reply floods the chat with duplicates. Buffer the latest snapshot and
  // emit ONCE when the turn completes (result event / process exit).
  private pendingAssistantText: string | undefined

  private dispatchLine(line: string): void {
    for (const ev of parseCursorStream(line)) {
      if (ev.kind === "init") {
        if (!this.sessionId) {
          this.sessionId = ev.session_id
          this.persistSessionId(ev.session_id).catch(() => {})
        }
      } else if (ev.kind === "assistant-message") {
        this.pendingAssistantText = ev.text
      } else if (ev.kind === "tool-call") {
        this.emit("tool-call", { kind: "tool-call", tool: ev.tool, phase: ev.phase, call_id: ev.call_id, detail: ev.detail })
      } else if (ev.kind === "result") {
        this.flushAssistant()
      }
    }
  }

  private flushAssistant(): void {
    if (this.pendingAssistantText != null) {
      this.emit("assistant-message", { kind: "assistant-message", text: this.pendingAssistantText })
      this.pendingAssistantText = undefined
    }
  }

  async interrupt(): Promise<void> {
    // Abort the in-flight turn's child. The runner's onExit fires on the kill,
    // which flushes any partial reply and emits turn-complete — a clean
    // turn-end, never an error. No active turn → harmless no-op.
    this.activeAbort?.abort()
  }
}
