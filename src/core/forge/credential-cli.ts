// src/core/forge/credential-cli.ts
// Standalone git credential helper. git invokes:  mux-credential '<connId>' <get|store|erase>
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

if (import.meta.main) {
  const connectionId = process.argv[2]
  const op = process.argv[3]
  if (op === "get" && connectionId) {
    try {
      const store = new ForgeStore(openDb(join(STATE_DIR, "db.sqlite3")))
      process.stdout.write(credentialFill(store, connectionId))
    } catch { /* emit nothing → git proceeds credential-less */ }
  }
}
