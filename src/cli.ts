// Entry point of the compiled `supermux` binary (and runnable via
// `bun src/cli.ts` in source mode — behavior is identical).
//
//   supermux            → broker (src/main.ts)
//   supermux shim       → MCP shim (src/shim/index.ts) — stdio-pure, no logging here
//   supermux pair <dev> → scripts/pair.ts   (argv shifted: script reads argv[2])
//   supermux revoke <d> → scripts/revoke.ts (argv shifted)
//   supermux version    → "X.Y.Z (commit)"
//   supermux setup      → write .env + user systemd unit + agent-CLI report (src/cli-setup.ts)
//   supermux connect    → set up a public/mesh tunnel + re-pair (src/cli-connect.ts)
//   supermux update     → check/apply an update (src/cli-update.ts)
//   supermux rollback   → revert to the previous binary (src/cli-update.ts)
//   supermux credential → git credential helper for forge HTTPS (src/core/forge/credential-cli.ts)
//   supermux __core-keeper → supermux-core's session keeper (spawned by the core, env-configured)
//   supermux __core-bridge → supermux-core's host-MCP bridge (spawned by an agent from its MCP config)
//     A compiled binary has no keeper.js/bridge.js on disk, so the core launches these as
//     `<binary> <subcommand>` (packages/supermux-core/src/self-entry.ts CORE_SUBCOMMANDS).
//   supermux help | session | msg | delegate | council | card | attach | file | port | memory | comments | display |
//     device | docs | api  → the agent/human CLI over the session socket (src/cli/agent.ts, #156)
//   supermux __speech-models-check → load the dictation stream's speech models in their workers
//     and score one stretch of audio (scripts/smoke-binary.sh check 6)
//
// Dynamic imports are string literals so `bun build --compile` bundles them.
import { versionString } from "./shared/build-info"

const sub = process.argv[2]

switch (sub) {
  case undefined:
    await import("./main")
    break
  case "shim":
    process.argv.splice(2, 1)
    await import("./shim/index")
    break
  case "pair":
    process.argv.splice(2, 1)
    await import("../scripts/pair")
    break
  case "revoke":
    process.argv.splice(2, 1)
    await import("../scripts/revoke")
    break
  case "version":
    console.log(versionString())
    break
  case "setup": {
    const { runSetupCommand } = await import("./cli-setup")
    process.exit(await runSetupCommand(process.argv.slice(3)))
  }
  case "connect": {
    const { runConnectCommand } = await import("./cli-connect")
    process.exit(await runConnectCommand(process.argv.slice(3)))
  }
  case "update": {
    const { runUpdateCommand } = await import("./cli-update")
    process.exit(await runUpdateCommand(process.argv.slice(3)))
  }
  case "rollback": {
    const { runRollbackCommand } = await import("./cli-update")
    process.exit(await runRollbackCommand(process.argv.slice(3)))
  }
  case "credential": {
    // Git credential helper for forge HTTPS remotes (see src/core/forge/launcher.ts).
    // argv after splice: [exec, <connId>, get|store|erase]
    process.argv.splice(2, 1)
    const { runCredentialHelper } = await import("./core/forge/credential-cli")
    runCredentialHelper()
    break
  }
  case "__core-keeper":
    process.argv.splice(2, 1)
    await import("../packages/supermux-core/src/keeper/keeper")
    break
  case "__core-bridge":
    process.argv.splice(2, 1)
    await import("../packages/supermux-core/src/mcp/bridge")
    break
  case "__speech-models-check": {
    const { runSpeechModelsCheck } = await import("./core/transcription/stream/check")
    process.exit(await runSpeechModelsCheck())
  }
  case "help": case "--help": case "-h":
  case "session": case "s": case "msg": case "delegate": case "council": case "card": case "attach": case "file":
  case "port": case "memory": case "comments": case "display": case "device": case "docs": case "api": case "applet": {
    const { runAgentCli } = await import("./cli/agent")
    const { readFileSync } = await import("node:fs")
    const { spawnProcessSync } = await import("./core/process/launcher")
    const { writeAll } = await import("./cli/write-all")
    process.exit(await runAgentCli(process.argv.slice(2), {
      env: process.env, cwd: process.cwd(),
      // Written synchronously: process.exit() right after an async write drops whatever a pipe hasn't taken yet, so
      // `supermux … --json | python3` got the first 64 KiB only (#156, 2026-11-26).
      out: (s) => writeAll(1, s + "\n"), err: (s) => writeAll(2, s + "\n"),
      stdin: () => readFileSync(0, "utf8"),
      // `supermux applet APPLET CMD …` (#117): the applet's own command, in this terminal.
      exec: (file, args, opts) => spawnProcessSync([file, ...args], { cwd: opts.cwd, env: opts.env, stdin: "inherit", stdout: "inherit", stderr: "inherit" }).exitCode ?? 1,
    }))
  }
  default:
    console.error(`supermux: unknown subcommand '${sub}'`)
    console.error(
      "usage: supermux [shim|pair <device>|revoke <device>|version|setup|connect|update|rollback|credential] · in a session: supermux help",
    )
    process.exit(2)
}
