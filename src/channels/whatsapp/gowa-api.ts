import { readFile } from "fs/promises"
import { basename } from "path"

export interface GowaClientOpts {
  baseUrl: string
  basicAuth?: string         // "user:pass"
  deviceId?: string          // X-Device-Id (GOWA v8 multi-device); optional
  fetchImpl?: typeof fetch   // injectable for tests
}

export interface GowaSendResult { message_id: string }
export type GowaMediaKind = "image" | "file" | "audio"

export class GowaClient {
  constructor(private readonly opts: GowaClientOpts) {}

  private get f(): typeof fetch { return this.opts.fetchImpl ?? fetch }

  private headers(extra?: Record<string, string>): Record<string, string> {
    const h: Record<string, string> = { ...(extra ?? {}) }
    if (this.opts.basicAuth) h["Authorization"] = "Basic " + Buffer.from(this.opts.basicAuth).toString("base64")
    if (this.opts.deviceId) h["X-Device-Id"] = this.opts.deviceId
    return h
  }

  async sendText(phone: string, message: string, replyTo?: string): Promise<GowaSendResult> {
    const res = await this.f(`${this.opts.baseUrl}/send/message`, {
      method: "POST",
      headers: this.headers({ "Content-Type": "application/json" }),
      body: JSON.stringify({ phone, message, ...(replyTo ? { reply_message_id: replyTo } : {}) }),
    })
    return this.parseSend(res)
  }

  async sendMedia(kind: GowaMediaKind, phone: string, filePath: string, opts?: { caption?: string; replyTo?: string }): Promise<GowaSendResult> {
    const bytes = await readFile(filePath)
    const form = new FormData()
    form.set("phone", phone)
    const field = kind === "image" ? "image" : kind === "audio" ? "audio" : "file"
    form.set(field, new Blob([bytes]), basename(filePath))
    if (opts?.caption) form.set("caption", opts.caption)
    if (opts?.replyTo) form.set("reply_message_id", opts.replyTo)
    const res = await this.f(`${this.opts.baseUrl}/send/${kind}`, { method: "POST", headers: this.headers(), body: form })
    return this.parseSend(res)
  }

  private async parseSend(res: Response): Promise<GowaSendResult> {
    if (!res.ok) throw new Error(`gowa send failed: ${res.status} ${await res.text().catch(() => "")}`)
    const j: any = await res.json()
    const id = j?.results?.message_id
    if (!id) throw new Error("gowa send: no results.message_id in response")
    return { message_id: String(id) }
  }

  async status(): Promise<{ is_connected: boolean; is_logged_in: boolean }> {
    const res = await this.f(`${this.opts.baseUrl}/app/status`, { headers: this.headers() })
    const j: any = await res.json().catch(() => ({}))
    const r = j?.results ?? {}
    return { is_connected: !!r.is_connected, is_logged_in: !!r.is_logged_in }
  }

  async fetchMedia(pathOrUrl: string): Promise<Uint8Array> {
    const url = /^https?:\/\//.test(pathOrUrl) ? pathOrUrl : `${this.opts.baseUrl}/${pathOrUrl.replace(/^\//, "")}`
    const res = await this.f(url, { headers: this.headers() })
    if (!res.ok) throw new Error(`gowa media fetch failed: ${res.status}`)
    return new Uint8Array(await res.arrayBuffer())
  }

  async downloadMedia(messageId: string, phone: string): Promise<string> {
    const res = await this.f(`${this.opts.baseUrl}/message/${encodeURIComponent(messageId)}/download?phone=${encodeURIComponent(phone)}`, { headers: this.headers() })
    const j: any = await res.json().catch(() => ({}))
    const fileUrl = j?.results?.file_url
    if (!fileUrl) throw new Error("gowa download: no results.file_url")
    return String(fileUrl)
  }
}
