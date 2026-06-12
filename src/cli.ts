// Entry point of the compiled `supermux` binary (and runnable via
// `bun src/cli.ts` in source mode — behavior is identical).
//
//   supermux            → broker (src/main.ts)
//   supermux shim       → MCP shim (src/shim/index.ts) — stdio-pure, no logging here
//   supermux pair <dev> → scripts/pair.ts   (argv shifted: script reads argv[2])
//   supermux revoke <d> → scripts/revoke.ts (argv shifted)
//   supermux version    → "X.Y.Z (commit)"
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
  default:
    console.error(`supermux: unknown subcommand '${sub}'`)
    console.error("usage: supermux [shim|pair <device>|revoke <device>|version]")
    process.exit(2)
}
