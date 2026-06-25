import { verifyGowaSignature } from "./webhook-verify"
import { makeLogger } from "../../shared/log"

const log = makeLogger("channels/whatsapp/webhook")

export interface WebhookHandlerOpts {
  secret: string
  onMessage: (payload: any) => void
}

// Pure request handler — verifies HMAC, parses, filters to inbound message
// events, and fires onMessage with the inner `payload` object.
export function createWebhookHandler(opts: WebhookHandlerOpts): (req: Request) => Promise<Response> {
  return async (req) => {
    if (req.method !== "POST") return new Response("method not allowed", { status: 405 })
    const raw = await req.text()
    const sig = req.headers.get("X-Hub-Signature-256")
    if (!verifyGowaSignature(raw, sig, opts.secret)) {
      log.warn("webhook_bad_signature")
      return new Response("invalid signature", { status: 401 })
    }
    let body: any
    try {
      body = JSON.parse(raw)
    } catch {
      return new Response("bad json", { status: 400 })
    }
    if (body?.event === "message" && body?.payload && body.payload.is_from_me !== true) {
      try {
        opts.onMessage(body.payload)
      } catch (err: any) {
        log.error("webhook_onmessage_threw", { err: err?.message ?? String(err) })
      }
    }
    return new Response("ok")
  }
}

// Thin localhost Bun.serve wrapper around the handler.
export class WhatsAppWebhookServer {
  private server?: ReturnType<typeof Bun.serve>
  constructor(private readonly port: number, private readonly handler: (req: Request) => Promise<Response>) {}
  start(): void {
    this.server = Bun.serve({ port: this.port, hostname: "127.0.0.1", fetch: this.handler })
  }
  async stop(): Promise<void> {
    this.server?.stop(true)
  }
  get boundPort(): number {
    return this.server?.port ?? this.port
  }
}
