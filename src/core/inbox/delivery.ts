// src/core/inbox/delivery.ts
import { makeLogger } from "../../shared/log"
import type { Session } from "../session-manager/types"
import type { Message } from "../session-manager/messages"
import type { InboundDeliveryResult } from "../session-manager/inbound-delivery"
import type { InboxMessage, InboxSender, InboxStore } from "./store"

const log = makeLogger("inbox/delivery")

export type InboxBusy = "queue" | "interrupt"

export type InboxDeliveryDeps = {
  store: InboxStore
  cursor: { get(sessionId: string): string | undefined; set(sessionId: string, messageId: string): void }
  messageLog: { append(sessionId: string, entry: Message): void }
  /** The session row, archived ones included (registry.sessions.getById). */
  session(id: string): Session | undefined
  resumeFromArchive(id: string): Promise<{ ok: boolean; error?: string }>
  resumeSuspended(session: Session): Promise<boolean>
  /** "none": no adapter registered; "dead": its core session failed or closed. */
  adapterState(id: string): "none" | "alive" | "dead"
  resumeAdapter(id: string): Promise<void>
  waitDeliverable(id: string, timeoutMs: number): Promise<boolean>
  /** SessionManager.deliver. Resolves when the turn it starts has finished. */
  deliver(id: string, text: string, meta: Record<string, string>): Promise<InboundDeliveryResult>
  deliverableTimeoutMs?: number
}

/** What the recipient agent reads (spec §1.4). */
export function envelope(message: InboxMessage): string {
  return `Message from [${message.sender.name}:${message.sender.id}] (${message.id}):\n${message.text}`
}

const errText = (err: unknown) => (err instanceof Error ? err.message : String(err))

export class InboxDelivery {
  private readonly chains = new Map<string, Promise<void>>()
  /** Busy mode of the messages this process appended. After a restart every message queues. */
  private readonly busyOf = new Map<string, InboxBusy>()

  constructor(private readonly deps: InboxDeliveryDeps) {}

  /** message_session (spec §1.2). Throws before writing anything when a recipient is unknown. */
  send(from: InboxSender, to: string[], text: string, busy: InboxBusy): { ids: Record<string, string> } {
    const unknown = to.filter((id) => !this.deps.session(id))
    if (unknown.length) throw new Error(`unknown session id: ${unknown.join(", ")}`)
    const ids: Record<string, string> = {}
    for (const id of new Set(to)) {
      const message = this.deps.store.append(id, { sender: from, text })
      this.busyOf.set(message.id, busy)
      ids[id] = message.id
      this.kick(id)
    }
    return { ids }
  }

  /** Startup (spec §1.3, step 5): deliver everything after each recipient's cursor. */
  recoverOnStartup(): Promise<void> {
    for (const id of this.deps.store.sessions()) {
      if (this.deps.session(id)) this.kick(id)
    }
    return this.idle()
  }

  /** Resolves once no delivery pass is running (tests, startup). */
  async idle(): Promise<void> {
    while (this.chains.size) await Promise.all([...this.chains.values()])
  }

  /** One delivery pass per recipient at a time, so a recipient gets its messages in file order. */
  private kick(id: string): void {
    const previous = this.chains.get(id) ?? Promise.resolve()
    const next: Promise<void> = previous
      .then(() => this.deliverPending(id))
      .catch((err) => { log.warn("inbox_delivery_failed", { session: id, err: errText(err) }) })
      .finally(() => { if (this.chains.get(id) === next) this.chains.delete(id) })
    this.chains.set(id, next)
  }

  private async deliverPending(id: string): Promise<void> {
    const pending = this.deps.store.after(id, this.deps.cursor.get(id))
    if (!pending.length) return
    await this.ensureLive(id)
    for (const message of pending) {
      const busy = this.busyOf.get(message.id) ?? "queue"
      this.busyOf.delete(message.id)
      this.logMessage(id, message)
      const meta = {
        message_id: `inbox:${message.id}`,
        ts: message.at,
        user: message.sender.name,
        user_id: message.sender.id,
        system_generated: "inbox",
        busy,
      }
      // deliver() resolves only when the turn it starts has ended. The adapter takes input in call
      // order, so hand each message over without waiting for its turn, and mark it delivered now.
      // busy goes through as sent: the adapter ends only the turn running at this call, so a second
      // interrupt never cuts off the turn the first one lined up.
      const turn = this.deps.deliver(id, envelope(message), meta)
      void turn.then(
        (r) => { if (!r.ok) log.warn("inbox_deliver_not_ready", { session: id, message: message.id, reason: r.reason }) },
        (err) => { log.warn("inbox_deliver_threw", { session: id, message: message.id, err: errText(err) }) },
      )
      this.deps.cursor.set(id, message.id)
    }
  }

  /** Spec §1.3, steps 0–2: archived → restore, suspended → resume, crashed → resume the adapter. */
  private async ensureLive(id: string): Promise<void> {
    const row = this.deps.session(id)
    if (!row) throw new Error(`session ${id} no longer exists`)
    if (row.status === "archived") {
      const restored = await this.deps.resumeFromArchive(id)
      if (!restored.ok) throw new Error(`restore from archive failed: ${restored.error ?? "unknown"}`)
    } else if (row.status === "suspended") {
      if (!(await this.deps.resumeSuspended(row))) throw new Error("resuming the suspended session failed")
    }
    if (this.deps.adapterState(id) === "dead") await this.deps.resumeAdapter(id)
    if (!(await this.deps.waitDeliverable(id, this.deps.deliverableTimeoutMs ?? 60_000))) {
      throw new Error("session did not become deliverable")
    }
  }

  private logMessage(id: string, message: InboxMessage): void {
    try {
      this.deps.messageLog.append(id, {
        id: `in:inbox:${message.id}`,
        ts: message.at,
        direction: "inbound",
        channel: "inbox",
        // Never a reply destination: lastInboundChatId() skips empty chat ids.
        chat_id: "",
        message_id: message.id,
        text: message.text,
        from_session_id: message.sender.id,
      })
    } catch (err) {
      // Same id already logged by a pass that crashed before delivering.
      log.info("inbox_log_skip", { session: id, message: message.id, err: errText(err) })
    }
  }
}
