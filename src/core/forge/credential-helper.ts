import { execFileSync } from "child_process"

/** The git credential-helper command string. The broker resolves <connId>'s token at fill time. */
export function helperCommand(connectionId: string): string {
  // `!` makes git run this as a shell command; bun runs our entrypoint (see main.ts wiring in Plan 2).
  return `!mux-credential ${connectionId}`
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
