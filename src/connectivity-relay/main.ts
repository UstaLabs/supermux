import { createRelayControl, FileKnownHostRegistry } from "../core/relay/control"
import { makeLogger } from "../shared/log"

function positiveInt(value: string | undefined, fallback: number): number {
  const parsed = Number(value)
  return Number.isSafeInteger(parsed) && parsed > 0 ? parsed : fallback
}

const env = process.env
const secret = env.MUX_CONNECTIVITY_RELAY_SECRET ?? ""
if (Buffer.byteLength(secret) < 32) throw new Error("set MUX_CONNECTIVITY_RELAY_SECRET to at least 32 random bytes")

const port = positiveInt(env.MUX_CONNECTIVITY_RELAY_PORT, 7200)
const domain = env.MUX_CONNECTIVITY_RELAY_DOMAIN ?? "relay.supermux.dev"
const registryPath = env.MUX_CONNECTIVITY_RELAY_HOSTS ?? "/var/lib/supermux-relay/hosts.json"
const log = makeLogger("connectivity-relay")
const control = createRelayControl({
  secret,
  domain,
  knownHosts: new FileKnownHostRegistry(registryPath),
  leaseTtlMs: positiveInt(env.MUX_CONNECTIVITY_RELAY_LEASE_HOURS, 24) * 60 * 60 * 1000,
  ratePerMinute: positiveInt(env.MUX_CONNECTIVITY_RELAY_RATE_PER_MIN, 60),
  onAuthRejected: (event) => log.warn("relay_auth_rejected", { ...event }),
})

Bun.serve({
  hostname: "127.0.0.1",
  port,
  fetch(req, server) {
    return control.handle(req, server.requestIP(req)?.address ?? "unknown")
  },
})
log.info("connectivity_relay_ready", { port, domain, registryPath })
