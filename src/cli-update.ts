// CLI subcommand bodies for `supermux update` and `supermux rollback`.
// Kept here to leave src/cli.ts a thin dispatcher.
//
// Both exported functions:
//   - accept an optional `println` for testability (defaults to console.log)
//   - return an exit code (0 = success, 1 = error) — never call process.exit()
import { BUILD_COMMIT, BUILD_VERSION } from "./shared/build-info"
import { UpdateChecker } from "./core/update/checker"
import { resolveAndApply, restartService, rollback } from "./core/update/apply"
import { askBrokerToRestart, localBrokerPort } from "./core/tunnels/public-url"
import { STATE_DIR } from "./shared/paths"
import { detectUpdateMode } from "./core/update/mode"

const DEFAULT_URL = "https://supermux.dev/versions.json"

function resolveUrl(): string {
  return process.env.MUX_UPDATE_URL ?? DEFAULT_URL
}

/**
 * After an update or a rollback swapped the binary: the running broker restarts itself under what runs it, as for
 * `supermux connect --restart`. It alone knows what that is, and a restart it scheduled leaves its agents running for
 * the broker that comes back, where a service restarted from here looks to it like a plain stop (#29). A broker that
 * cannot be asked (none answers, or an older one) is restarted from here, as before. Says which happened, or what to
 * do when the broker cannot restart itself; true when a restart is on its way.
 */
export async function restartAfterSwap(println: (s: string) => void, deps: {
  stateDir?: string
  fetchImpl?: typeof fetch
  restartService?: () => boolean
} = {}): Promise<boolean> {
  const stateDir = deps.stateDir ?? STATE_DIR
  const answer = await askBrokerToRestart(stateDir, localBrokerPort(stateDir), deps.fetchImpl)
  if (answer === "restarting") {
    println("Asked the running broker to restart: its sessions keep running.")
    return true
  }
  if (answer === "unsupervised") {
    println("The broker does not run under a service manager, so it cannot restart itself: restart it yourself (quit and reopen the supermux app, or run it again).")
    return false
  }
  if (!(deps.restartService ?? restartService)()) return false
  println("No running broker answered: restarted its service from here.")
  return true
}

/**
 * `supermux update [--check]`
 *
 * --check: run a one-shot UpdateChecker, print status JSON, exit 0.
 * (no flag): mode-gated apply flow.
 *
 * @param args   process.argv.slice(3) from the dispatcher
 * @param println optional output sink (default console.log); injected by tests
 */
export async function runUpdateCommand(
  args: string[],
  println: (s: string) => void = console.log,
): Promise<number> {
  const url = resolveUrl()
  const mode = detectUpdateMode()

  // ── --check: one-shot checker, print status JSON ─────────────────────────
  if (args.includes("--check")) {
    const checker = new UpdateChecker({
      url,
      currentVersion: BUILD_VERSION,
      commit: BUILD_COMMIT,
      mode,
    })
    await checker.checkNow()
    println(JSON.stringify(checker.status(), null, 2))
    return 0
  }

  // ── no flag: apply flow ───────────────────────────────────────────────────
  if (mode === "source") {
    println("Source install — update with your git workflow (git pull && restart).")
    return 0
  }

  if (mode === "docker") {
    println("Docker install — update with: docker compose pull && docker compose up -d")
    return 0
  }

  if (mode === "managed") {
    println("Updated with the supermux app.")
    return 0
  }

  // mode === "binary": the only mode that swaps its own binary
  const result = await resolveAndApply({
    url,
    currentVersion: BUILD_VERSION,
    onState(s) {
      if (s === "checking") println("checking versions.json…")
      else if (s === "downloading") println("downloading new version…")
      else if (s === "swapping") println("swapping binaries…")
    },
  })

  if (result.ok) {
    println(`Updated to v${result.newVersion} (previous kept at ${result.prevPath}).`)
    if (!(await restartAfterSwap(println))) {
      println("Restart the broker to finish — the new binary is on disk.")
    }
    return 0
  }

  const err = result.error
  switch (err.kind) {
    case "already-current":
      println(`Up to date (${BUILD_VERSION}).`)
      return 0
    case "busy":
      println("Another update/rollback is in progress.")
      return 1
    case "manifest-unavailable":
      println(`Update failed: manifest unavailable — ${err.detail}`)
      return 1
    default:
      // arch-unsupported | asset-missing | download-failed | sha-mismatch | swap-failed
      println(`Update failed: ${err.kind}`)
      return 1
  }
}

/**
 * `supermux rollback`
 *
 * Calls the rollback engine and maps its result to exit codes + messages.
 *
 * @param args   process.argv.slice(3) (reserved for future flags)
 * @param println optional output sink (default console.log); injected by tests
 */
export async function runRollbackCommand(
  args: string[],
  println: (s: string) => void = console.log,
): Promise<number> {
  // rollback() is synchronous; wrap in try for safety.
  const result = rollback({})

  if (result.ok) {
    println(`Rolled back (restored from ${result.restoredFrom}).`)
    if (!(await restartAfterSwap(println))) {
      println("Restart the broker to finish — the previous binary is on disk.")
    }
    println(
      "Note: if the version you updated FROM added a database migration, the older binary may refuse to boot" +
      " (a safety guard against running old code on migrated state)." +
      " If the broker fails to come back, re-run `supermux update` to recover.",
    )
    return 0
  }

  const err = result.error
  switch (err.kind) {
    case "no-prev":
      println("Nothing to roll back to (.prev not found next to the binary).")
      return 1
    case "busy":
      println("Another update/rollback is in progress.")
      return 1
    case "swap-failed":
      println(`Rollback failed: ${err.detail}`)
      return 1
  }
}
