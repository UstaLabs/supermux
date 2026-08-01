import { mkdirSync } from "fs"
import { join } from "path"
import { DeviceStore } from "../src/channels/web/device-store"
import { Registry } from "../src/core/session-manager/registry"
import { openDb, runMigrations } from "../src/core/storage/db"
import { MIGRATIONS } from "../src/core/storage/migrations"

const stateDir = process.env.MUX_STATE_DIR
const workdir = process.env.MUX_TEST_WORKDIR
if (!stateDir || !workdir) {
  throw new Error("MUX_STATE_DIR and MUX_TEST_WORKDIR are required")
}

export const TEST_SESSION_ID = "00000000-0000-4000-8000-000000000001"
export const TEST_SESSION_NAME = "test-journey"
export const TEST_DEVICE_NAME = "playwright-fixture"

mkdirSync(stateDir, { recursive: true, mode: 0o700 })
mkdirSync(workdir, { recursive: true })

const db = openDb(join(stateDir, "db.sqlite3"))
runMigrations(db, MIGRATIONS)
const registry = new Registry(db)
registry.register({
  id: TEST_SESSION_ID,
  name: TEST_SESSION_NAME,
  agent: "claude",
  workdir,
  tmux_target: `test:${TEST_SESSION_NAME}`,
  pid: 0,
})
db.close()

const { token } = new DeviceStore(join(stateDir, "devices.json")).mint(TEST_DEVICE_NAME)
console.log(JSON.stringify({
  sessionId: TEST_SESSION_ID,
  sessionName: TEST_SESSION_NAME,
  deviceName: TEST_DEVICE_NAME,
  token,
}))
