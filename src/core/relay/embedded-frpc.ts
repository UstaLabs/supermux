import { FrpClient, FrpError, type FrpEvent } from "../../../packages/frp-client/src/index.js"
import type { Logger } from "../../shared/log"
import type { FrpChild, FrpTunnelConfig } from "./frp-provider"

export interface EmbeddedFrpcOptions {
  /** Check the relay's certificate (off only while frps still serves its self-signed one). */
  verifyTls: boolean
  /** An http:// proxy to reach frps through (frpc honours http_proxy the same way). */
  httpProxy?: string
  log: Pick<Logger, "info" | "warn">
  /** For tests: a CA and server name to verify against instead of the system roots and serverAddr. */
  tls?: { ca?: string; serverName?: string }
}

/**
 * FrpRelayProvider's `start`: the relay tunnel as an in-process @supermux/frp-client session instead of an frpc
 * process. Each child is one session, connecting on activate; a child replacing another passes the last run id,
 * so frps drops the old session at once instead of refusing the new one's proxy as already taken.
 */
export function embeddedFrpc(options: EmbeddedFrpcOptions): (tunnel: FrpTunnelConfig) => FrpChild {
  let lastRunId: string | undefined
  return (tunnel) => {
    const client = new FrpClient({
      serverAddr: tunnel.serverAddr,
      serverPort: tunnel.serverPort,
      tls: { verify: options.verifyTls, ...options.tls },
      metadatas: { lease: tunnel.lease },
      proxies: [{
        name: tunnel.proxyName,
        type: "http",
        subdomain: tunnel.subdomain,
        metadatas: { lease: tunnel.lease },
        localIP: "127.0.0.1",
        localPort: tunnel.localPort,
      }],
      ...(lastRunId ? { runId: lastRunId } : {}),
      ...(options.httpProxy ? { httpProxy: options.httpProxy } : {}),
      onEvent: (event: FrpEvent) => {
        if (event.type === "local_dial_failed") options.log.warn("relay_local_dial_failed", { code: event.code })
        else if (event.type === "work_conn_failed") options.log.warn("relay_work_conn_failed", { reason: event.reason })
        else if (event.type === "closed") options.log.info("relay_client_closed", { reason: event.reason })
      },
    })
    return {
      async activate() {
        try {
          lastRunId = await client.start()
        } catch (error) {
          // FrpError messages carry the server's reason or a socket code, never the lease.
          options.log.warn("relay_client_start_failed", error instanceof FrpError
            ? { code: error.code, reason: error.message }
            : { code: "unknown" })
          throw error
        }
      },
      kill: () => client.close(),
      exited: client.closed,
    }
  }
}
