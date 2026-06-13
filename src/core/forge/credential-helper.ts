import { execFileSync } from "child_process"

/** The git credential-helper command string. The broker resolves <connId>'s token at fill time. */
export function helperCommand(connectionId: string): string {
  // `!` makes git run this via `sh -c`; single-quote the id so shell metacharacters
  // in a connection id can never inject. (sh single-quote escape: ' -> '\'' )
  return `!mux-credential '${connectionId.replace(/'/g, "'\\''")}'`
}

/** Bind a cloned repo to its connection: future push/fetch/pull over this host use the helper. */
export function bindHttpsCredentials(repoPath: string, host: string, connectionId: string): void {
  execFileSync("git", ["-C", repoPath, "config", "--local", `credential.https://${host}.helper`, helperCommand(connectionId)])
}

/** Produce git's credential-fill stdout for a connection. `lookup` returns the live token from the store. */
export function resolveCredentialFill(
  connectionId: string,
  lookup: (id: string) => { user: string; token: string } | undefined,
): string {
  const cred = lookup(connectionId)
  if (!cred) return "" // git treats empty as "no credential"
  return `username=${cred.user}\npassword=${cred.token}\n`
}
