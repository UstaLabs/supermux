import { createPublicKey, randomBytes, verify as verifySignature } from "crypto"
import { chmodSync, existsSync, mkdirSync, readFileSync, renameSync, writeFileSync } from "fs"
import { dirname } from "path"
import { hostIdFromPublicKey } from "../host-identity"
import { handleAuthOp } from "./auth-plugin"
import { mintLease } from "./lease"

const HOST_ID = /^[a-z2-7]{26}$/
const ED25519_SPKI_PREFIX = Buffer.from("302a300506032b6570032100", "hex")

interface KnownHostRegistry {
  get(hostId: string): string | undefined
  register(hostId: string, publicKey: string): "registered" | "known" | "mismatch"
}

class MapKnownHostRegistry implements KnownHostRegistry {
  constructor(protected readonly hosts = new Map<string, string>()) {}
  get(hostId: string): string | undefined { return this.hosts.get(hostId) }
  register(hostId: string, publicKey: string): "registered" | "known" | "mismatch" {
    const existing = this.hosts.get(hostId)
    if (existing && existing !== publicKey) return "mismatch"
    if (existing) return "known"
    this.hosts.set(hostId, publicKey)
    return "registered"
  }
}

export class FileKnownHostRegistry extends MapKnownHostRegistry {
  constructor(private readonly path: string) {
    super(FileKnownHostRegistry.load(path))
  }

  override register(hostId: string, publicKey: string): "registered" | "known" | "mismatch" {
    const result = super.register(hostId, publicKey)
    if (result === "registered") this.persist()
    return result
  }

  private static load(path: string): Map<string, string> {
    if (!existsSync(path)) return new Map()
    const parsed = JSON.parse(readFileSync(path, "utf8")) as Record<string, unknown>
    return new Map(Object.entries(parsed).filter(([id, key]) => HOST_ID.test(id) && typeof key === "string") as [string, string][])
  }

  private persist(): void {
    mkdirSync(dirname(this.path), { recursive: true, mode: 0o700 })
    const tmp = `${this.path}.${process.pid}.tmp`
    writeFileSync(tmp, JSON.stringify(Object.fromEntries(this.hosts), null, 2) + "\n", { mode: 0o600 })
    renameSync(tmp, this.path)
    chmodSync(this.path, 0o600)
  }
}

export interface RelayControlOpts {
  secret: string
  domain: string
  knownHosts?: Map<string, string> | KnownHostRegistry
  leaseTtlMs?: number
  nonceTtlMs?: number
  ratePerMinute?: number
  now?: () => number
  random?: () => string
}

function json(value: unknown, status = 200): Response {
  return new Response(JSON.stringify(value), {
    status,
    headers: { "content-type": "application/json", "cache-control": "no-store" },
  })
}

export function createRelayControl(opts: RelayControlOpts) {
  if (Buffer.byteLength(opts.secret) < 32) throw new Error("relay secret must be at least 32 bytes")
  const domain = opts.domain.toLowerCase().replace(/^\.+|\.+$/g, "")
  if (!domain.includes(".")) throw new Error("relay domain must be a DNS name")
  const now = opts.now ?? Date.now
  const leaseTtlMs = opts.leaseTtlMs ?? 24 * 60 * 60 * 1000
  const nonceTtlMs = opts.nonceTtlMs ?? 60_000
  const random = opts.random ?? (() => randomBytes(24).toString("base64url"))
  const registry: KnownHostRegistry = opts.knownHosts instanceof Map
    ? new MapKnownHostRegistry(opts.knownHosts)
    : opts.knownHosts ?? new MapKnownHostRegistry()
  const nonces = new Map<string, number>()
  const rates = new Map<string, { minute: number; count: number }>()
  const ratePerMinute = opts.ratePerMinute ?? 60

  function allow(ip: string): boolean {
    const minute = Math.floor(now() / 60_000)
    const current = rates.get(ip)
    if (!current || current.minute !== minute) { rates.set(ip, { minute, count: 1 }); return true }
    current.count++
    return current.count <= ratePerMinute
  }

  function issueNonce(): string {
    const timestamp = now()
    for (const [nonce, expiry] of nonces) if (timestamp > expiry) nonces.delete(nonce)
    while (nonces.size >= 10_000) nonces.delete(nonces.keys().next().value!)
    const nonce = random()
    nonces.set(nonce, timestamp + nonceTtlMs)
    return nonce
  }

  function consumeNonce(nonce: string): boolean {
    const expiry = nonces.get(nonce)
    if (expiry === undefined) return false
    nonces.delete(nonce)
    return now() <= expiry
  }

  async function handle(req: Request, remoteIp = "local"): Promise<Response> {
    const url = new URL(req.url)
    const path = url.pathname
    if (path === "/healthz" && req.method === "GET") return json({ ok: true })

    if ((path === "/relay/nonce" || path === "/relay/lease") && !allow(remoteIp)) {
      return json({ error: "rate limited" }, 429)
    }

    if (path === "/relay/nonce" && req.method === "GET") return json({ nonce: issueNonce() })

    if (path === "/relay/lease" && req.method === "POST") {
      const contentLength = Number(req.headers.get("content-length") ?? 0)
      if (contentLength > 4096) return json({ error: "request too large" }, 413)
      const body = await req.json().catch(() => null) as null | Record<string, unknown>
      const hostId = typeof body?.hostId === "string" ? body.hostId : ""
      const publicKey = typeof body?.publicKey === "string" ? body.publicKey : ""
      const nonce = typeof body?.nonce === "string" ? body.nonce : ""
      const signature = typeof body?.signature === "string" ? body.signature : ""
      if (!HOST_ID.test(hostId) || !publicKey || !nonce || !signature || !consumeNonce(nonce)) {
        return json({ error: "invalid proof" }, 401)
      }
      try {
        const rawKey = Buffer.from(publicKey, "base64url")
        const rawSignature = Buffer.from(signature, "base64url")
        if (rawKey.length !== 32 || rawSignature.length !== 64 || hostIdFromPublicKey(rawKey) !== hostId) {
          return json({ error: "invalid proof" }, 401)
        }
        const key = createPublicKey({ key: Buffer.concat([ED25519_SPKI_PREFIX, rawKey]), format: "der", type: "spki" })
        if (!verifySignature(null, Buffer.from(nonce), key, rawSignature)) return json({ error: "invalid proof" }, 401)
        if (registry.register(hostId, publicKey) === "mismatch") return json({ error: "host key mismatch" }, 409)
        const issuedAt = now()
        return json({
          lease: mintLease({ hostId, secret: opts.secret, ttlMs: leaseTtlMs, now: issuedAt }),
          expiresAt: issuedAt + leaseTtlMs,
        })
      } catch {
        return json({ error: "invalid proof" }, 401)
      }
    }

    if (path === "/relay/caddy-ask" && req.method === "GET") {
      const requested = (url.searchParams.get("domain") ?? "").toLowerCase()
      if (requested === `control.${domain}`) return new Response(null, { status: 200 })
      const suffix = `.${domain}`
      if (!requested.endsWith(suffix)) return new Response(null, { status: 403 })
      const label = requested.slice(0, -suffix.length)
      const hostId = label.startsWith("h-") ? label.slice(2) : ""
      return new Response(null, { status: HOST_ID.test(hostId) && registry.get(hostId) ? 200 : 403 })
    }

    if (path === "/handler" && req.method === "POST") {
      const operation = await req.json().catch(() => null)
      if (!operation || typeof operation !== "object") return json({ reject: true, reject_reason: "invalid request" })
      return json(handleAuthOp(operation as Parameters<typeof handleAuthOp>[0], { secret: opts.secret, now }))
    }

    return json({ error: "not found" }, 404)
  }

  return { handle }
}
