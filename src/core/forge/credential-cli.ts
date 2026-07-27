// src/core/forge/credential-cli.ts
// Standalone git credential helper. git invokes:  mux-credential '<connId>' <get|store|erase>
// On compiled installs the launcher is: supermux credential '<connId>' <op>
import { openDb } from "../storage/db"
import { ForgeStore } from "./store"
import { resolveCredentialFill } from "./credential-helper"
import { adapterFor } from "./registry"
import { STATE_DIR } from "../../shared/paths"
import { join } from "path"

/** Pure resolver — testable without a subprocess. */
export function credentialFill(store: ForgeStore, connectionId: string): string {
  return resolveCredentialFill(connectionId, (id) => {
    const c = store.getCredential(id)
    return c ? { user: adapterFor(c.kind).gitUser(), token: c.token } : undefined
  })
}

/** CLI entry used by both `bun credential-cli.ts` and `supermux credential`. */
export function runCredentialHelper(argv: string[] = process.argv): void {
  const connectionId = argv[2]
  const op = argv[3]
  if (op === "get" && connectionId) {
    try {
      const store = new ForgeStore(openDb(join(STATE_DIR, "db.sqlite3")))
      process.stdout.write(credentialFill(store, connectionId))
    } catch { /* emit nothing → git proceeds credential-less */ }
  }
}

if (import.meta.main) {
  runCredentialHelper()
}
