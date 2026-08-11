import { execFileSync } from "child_process"
import type { Scheme } from "./host"

/** The git credential-helper command string. The broker resolves <connId>'s token at fill time. */
export function helperCommand(helperPath: string, connectionId: string): string {
  // `!` makes git run this via `sh -c`; single-quote both args so shell metacharacters cannot inject.
  // (sh single-quote escape: ' -> '\'' )
  const q = (s: string) => `'${s.replace(/'/g, "'\\''")}'`
  return `!${q(helperPath)} ${q(connectionId)}`
}

/** Bind a cloned repo to its connection: future push/fetch/pull over this host use the helper.
 *  `scheme` must match the remote's scheme — git scopes credentials per-URL, so a helper
 *  registered under https:// is never consulted for an http:// remote. */
export function bindHttpsCredentials(repoPath: string, host: string, connectionId: string, helperPath: string, scheme: Scheme = "https"): void {
  execFileSync("git", ["-C", repoPath, "config", "--local", `credential.${scheme}://${host}.helper`, helperCommand(helperPath, connectionId)])
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
