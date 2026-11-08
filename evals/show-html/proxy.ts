// The check's network rule at the socket, as T3 Code's publicProxy.ts does it: Chrome sends every connection through
// this SOCKS5 proxy (with --proxy-bypass-list=<-loopback>, loopback too); the proxy resolves the name itself and
// refuses any private address, so a public name that resolves to 127.0.0.1, the LAN or Tailscale can't get through,
// and a rebinding name can't change between the check and the connect.
import { lookup } from "node:dns/promises"
import { isIP } from "node:net"
import type { Socket, TCPSocketListener } from "bun"
import { isPrivateHost } from "./check"

/** Private, loopback, link-local, CGNAT (Tailscale), NAT64 and 6to4 addresses. */
export function isPrivateAddress(ip: string): boolean {
  const a = ip.toLowerCase()
  if (a.startsWith("::ffff:")) return isPrivateAddress(a.slice(7))
  if (a.startsWith("64:ff9b:") || a.startsWith("2002:")) return true
  return isPrivateHost(a)
}

function withTimeout<T>(p: Promise<T>, ms: number): Promise<T> {
  return Promise.race([p, new Promise<T>((_, reject) => setTimeout(() => reject(new Error("timeout")), ms))])
}

interface Conn { stage: "greet" | "request" | "open"; buf: Buffer; upstream?: Socket<undefined> }

export async function startProxy(blockedHosts: string[] = []): Promise<{ port: number; stop(): void; refused: string[] }> {
  const refused: string[] = []
  const reply = (s: Socket<Conn>, code: number) => s.write(Buffer.from([5, code, 0, 1, 0, 0, 0, 0, 0, 0]))
  const server: TCPSocketListener<Conn> = Bun.listen<Conn>({
    hostname: "127.0.0.1",
    port: 0,
    socket: {
      open(s) { s.data = { stage: "greet", buf: Buffer.alloc(0) } },
      async data(s, chunk) {
        const c = s.data
        if (c.stage === "open") { c.upstream?.write(chunk); return }
        c.buf = Buffer.concat([c.buf, chunk])
        if (c.stage === "greet") {
          if (c.buf.length < 2 || c.buf.length < 2 + c.buf[1]!) return
          c.buf = c.buf.subarray(2 + c.buf[1]!)
          s.write(Buffer.from([5, 0]))
          c.stage = "request"
          if (!c.buf.length) return
        }
        if (c.buf.length < 5) return
        const atyp = c.buf[3]!
        let host = "", end = 0
        if (atyp === 1) { if (c.buf.length < 10) return; host = [...c.buf.subarray(4, 8)].join("."); end = 8 }
        else if (atyp === 3) { const n = c.buf[4]!; if (c.buf.length < 5 + n + 2) return; host = c.buf.subarray(5, 5 + n).toString(); end = 5 + n }
        else if (atyp === 4) {
          if (c.buf.length < 22) return
          host = c.buf.subarray(4, 20).toString("hex").match(/.{4}/g)!.join(":"); end = 20
        } else { reply(s, 8); s.end(); return }
        const port = c.buf.readUInt16BE(end)
        const rest = c.buf.subarray(end + 2)
        c.buf = Buffer.alloc(0)
        c.stage = "open"
        // Every address the name has must be public (a mixed answer is refused), and IPv4 is dialled first.
        let addresses: string[]
        try { addresses = isIP(host) ? [host] : (await lookup(host, { all: true })).map((a) => a.address) } catch { reply(s, 4); s.end(); return }
        const ip = addresses.find((a) => isIP(a) === 4) ?? addresses[0]!
        if (addresses.some(isPrivateAddress) || isPrivateHost(host) || blockedHosts.includes(host.toLowerCase())) {
          refused.push(`${host}:${port}`)
          reply(s, 2)
          s.end()
          return
        }
        try {
          c.upstream = await withTimeout(Bun.connect({
            hostname: ip, port,
            socket: {
              data(_u, d) { s.write(d) },
              close() { s.end() },
              error() { s.end() },
            },
          }), 5000)
          reply(s, 0)
          if (rest.length) c.upstream.write(rest)
        } catch { reply(s, 5); s.end() }
      },
      close(s) { s.data.upstream?.end() },
      error(s) { s.data.upstream?.end() },
    },
  })
  return { port: server.port, stop: () => server.stop(true), refused }
}
