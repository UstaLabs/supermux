# @supermux/frp-client

An [frp](https://github.com/fatedier/frp) client that runs inside your Node or Bun process, so there is no separate
`frpc` binary to ship, keep alive, or have quarantined: Windows Defender flags every frpc as `PUA:Win32/FRProxy`.

It speaks frp 0.61's protocol the way frpc does: one TLS connection to frps carrying a yamux session; a control
stream that logs in, then (AES-128-CFB encrypted) registers proxies and receives work-connection requests; and a
new stream per work connection, joined to the proxy's local address. No dependencies.

```ts
import { FrpClient } from '@supermux/frp-client'

const client = new FrpClient({
  serverAddr: 'frps.example.com',
  serverPort: 7000,
  metadatas: { lease },
  proxies: [{ name: 'web-host1', type: 'http', subdomain: 'h-host1', localIP: '127.0.0.1', localPort: 9898 }],
})
await client.start()          // connected, logged in, every proxy registered
const reason = await client.closed // the session ended; start a new client (pass client.runId) to reconnect
```

## Where it differs from frpc

- **It verifies the server's certificate** (system roots, or `tls.ca`). frpc does not unless given a CA file.
- **It does not reconnect by itself.** One `FrpClient` is one session; the owner decides when to start another.
  Passing the previous `runId` makes frps drop the old session at once, as frpc's own reconnect does.
- **Only `http` proxies, tcpMux on, the v1 protocol.** The parts frpc has beyond that (tcp/udp/stcp/xtcp proxies,
  visitors, QUIC/WebSocket transports, OIDC, per-proxy encryption and compression, health checks, the admin UI)
  are not implemented.

`httpProxy` reaches frps through an `http://` proxy with CONNECT, as frpc's `http_proxy` support does.

## Tests

`bun test tests` runs the yamux tests. The end-to-end suite runs against a real frps when `FRPS_BIN` points at
one (it needs `openssl` to mint a test CA): `FRPS_BIN=/path/to/frps bun test tests`.

## Credits

The protocol follows frp's own client (`client/`, `pkg/msg`) and its dependencies `fatedier/golib` (message
framing, crypto) and `fatedier/yamux`. [frpc.js](https://github.com/imzlh/frpc.js) (MIT) was a reference for a
TypeScript take on the same protocol.
