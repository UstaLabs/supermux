// The check's network rule at the socket, as T3 Code's publicProxy.ts does it: Chrome sends every connection through
// this SOCKS5 proxy (with <-loopback> bypassed, so loopback too); the proxy resolves the name itself and refuses it if
// any address it has is private, so a public name that resolves to 127.0.0.1, the LAN or Tailscale can't get through,
// and a rebinding name can't change between the check and the connect.
import { lookup } from "node:dns/promises"
import { BlockList, isIP } from "node:net"
import type { Socket, TCPSocketListener } from "bun"

const BLOCKED = new BlockList()
for (const [net, bits] of [
  ["0.0.0.0", 8], ["10.0.0.0", 8], ["100.64.0.0", 10], ["127.0.0.0", 8], ["169.254.0.0", 16], ["172.16.0.0", 12],
  ["192.0.0.0", 24], ["192.168.0.0", 16], ["198.18.0.0", 15], ["224.0.0.0", 4], ["240.0.0.0", 4],
] as const) BLOCKED.addSubnet(net, bits, "ipv4")
// IPv4-mapped addresses (::ffff:a.b.c.d) are checked against the IPv4 rules above by BlockList itself.
for (const [net, bits] of [
  ["::", 128], ["::1", 128], ["64:ff9b::", 96], ["64:ff9b:1::", 48], ["2001::", 32], ["2002::", 16],
  ["fc00::", 7], ["fe80::", 10], ["ff00::", 8],
] as const) BLOCKED.addSubnet(net, bits, "ipv6")

/** Loopback, private, link-local, CGNAT (Tailscale), benchmark, multicast, NAT64, 6to4, Teredo, ULA. Not an IP: refused. */
export function isPrivateAddress(ip: string): boolean {
  const a = ip.replace(/^\[|\]$/g, "")
  const v = isIP(a)
  return !v || BLOCKED.check(a, v === 4 ? "ipv4" : "ipv6")
}

/** Names that are local by definition, whatever DNS says. */
export function isLocalName(host: string): boolean {
  const h = host.toLowerCase().replace(/\.$/, "")
  return h === "localhost" || h.endsWith(".localhost") || h.endsWith(".local") || h.endsWith(".internal") || h.endsWith(".home.arpa")
}

function withTimeout<T>(p: Promise<T>, ms: number): Promise<T> {
  return Promise.race([p, new Promise<T>((_, reject) => setTimeout(() => reject(new Error("timeout")), ms))])
}

interface Conn { stage: "greet" | "request" | "connecting" | "open"; buf: Buffer; pending: Buffer[]; upstream?: Socket<undefined> }

export async function startProxy(blockedHosts: string[] = []): Promise<{ port: number; stop(): void; refused: string[] }> {
  const refused: string[] = []
  const reply = (s: Socket<Conn>, code: number) => s.write(Buffer.from([5, code, 0, 1, 0, 0, 0, 0, 0, 0]))
  const fail = (s: Socket<Conn>, code: number) => { reply(s, code); s.end() }
  const server: TCPSocketListener<Conn> = Bun.listen<Conn>({
    hostname: "127.0.0.1",
    port: 0,
    socket: {
      open(s) { s.data = { stage: "greet", buf: Buffer.alloc(0), pending: [] } },
      async data(s, chunk) {
        const c = s.data
        if (c.stage === "open") { c.upstream!.write(chunk); return }
        // Anything the client sends while the connection is being made waits for it.
        if (c.stage === "connecting") { c.pending.push(Buffer.from(chunk)); return }
        c.buf = Buffer.concat([c.buf, chunk])
        if (c.stage === "greet") {
          if (c.buf.length < 2 || c.buf.length < 2 + c.buf[1]!) return
          if (c.buf[0] !== 5) { s.end(); return }
          c.buf = c.buf.subarray(2 + c.buf[1]!)
          s.write(Buffer.from([5, 0]))
          c.stage = "request"
          if (!c.buf.length) return
        }
        if (c.buf.length < 5) return
        if (c.buf[0] !== 5 || c.buf[1] !== 1) { fail(s, 7); return } // CONNECT only
        const atyp = c.buf[3]!
        let host = "", end = 0
        if (atyp === 1) { if (c.buf.length < 10) return; host = [...c.buf.subarray(4, 8)].join("."); end = 8 }
        else if (atyp === 3) { const n = c.buf[4]!; if (c.buf.length < 5 + n + 2) return; host = c.buf.subarray(5, 5 + n).toString(); end = 5 + n }
        else if (atyp === 4) { if (c.buf.length < 22) return; host = c.buf.subarray(4, 20).toString("hex").match(/.{4}/g)!.join(":"); end = 20 }
        else { fail(s, 8); return }
        const port = c.buf.readUInt16BE(end)
        const rest = c.buf.subarray(end + 2)
        if (rest.length) c.pending.push(Buffer.from(rest))
        c.buf = Buffer.alloc(0)
        c.stage = "connecting"
        // Every address the name has must be public (a mixed answer is refused), and IPv4 is dialled first.
        let addresses: string[]
        try { addresses = isIP(host) ? [host] : (await withTimeout(lookup(host, { all: true }), 5000)).map((a) => a.address) } catch { fail(s, 4); return }
        if (!addresses.length || addresses.some(isPrivateAddress) || isLocalName(host) || blockedHosts.includes(host.toLowerCase())) {
          refused.push(`${host}:${port}`)
          fail(s, 2)
          return
        }
        const ip = addresses.find((a) => isIP(a) === 4) ?? addresses[0]!
        const early: Buffer[] = []
        let replied = false
        try {
          c.upstream = await withTimeout(Bun.connect({
            hostname: ip, port,
            socket: {
              // What the server says before the client has its reply waits behind it.
              data(_u, d) { if (replied) s.write(d); else early.push(Buffer.from(d)) },
              close() { s.end() },
              error() { s.end() },
            },
          }), 5000)
        } catch { fail(s, 5); return }
        reply(s, 0)
        replied = true
        for (const d of early.splice(0)) s.write(d)
        c.stage = "open"
        for (const d of c.pending.splice(0)) c.upstream.write(d)
      },
      close(s) { s.data.upstream?.end() },
      error(s) { s.data.upstream?.end() },
    },
  })
  return { port: server.port, stop: () => server.stop(true), refused }
}
