// One service name, two languages: the desktop app takes over a broker service `supermux setup` installed
// (apps/desktop/.../Takeover.kt) by the names src/cli-setup.ts gives it. Renamed on one side only, the app would no
// longer find, stop and replace the old service, and two brokers would claim one machine.
import { expect, test } from "bun:test"
import { readFileSync } from "fs"
import { join } from "path"

const root = join(import.meta.dir, "..")
const read = (path: string) => readFileSync(join(root, path), "utf8")

test("the desktop app's takeover knows the systemd unit and launchd label `supermux setup` installs", () => {
  const cli = read("src/cli-setup.ts")
  const unit = /const UNIT_NAME = "([^"]+)"/.exec(cli)?.[1]
  const label = /const LAUNCHD_LABEL = "([^"]+)"/.exec(cli)?.[1]
  expect(unit).toBeDefined()
  expect(label).toBeDefined()
  const takeover = read("apps/desktop/src/main/kotlin/dev/supermux/desktop/host/Takeover.kt")
  expect(takeover).toContain(`"${unit}"`)
  expect(takeover).toContain(`"${label}"`)
})
