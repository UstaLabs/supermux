import { execFileSync } from "child_process"

/** The git credential-helper command string. The broker resolves <connId>'s token at fill time. */
export function helperCommand(helperPath: string, connectionId: string): string {
  // `!` makes git run this via `sh -c`; single-quote both args so shell metacharacters cannot inject.
  // (sh single-quote escape: ' -> '\'' )
  const q = (s: string) => `'${s.replace(/'/g, "'\\''")}'`
  return `!${q(helperPath)} ${q(connectionId)}`
}

/** Bind a cloned repo to its connection: future push/fetch/pull over this host use the helper. */
export function bindHttpsCredentials(repoPath: string, host: string, connectionId: string, helperPath: string): void {
  execFileSync("git", ["-C", repoPath, "config", "--local", `credential.https://${host}.helper`, helperCommand(helperPath, connectionId)])
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
