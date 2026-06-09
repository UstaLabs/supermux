import { DeviceStore } from "../src/channels/web/device-store"
import { DEVICES_FILE } from "../src/shared/paths"

const name = process.argv[2]
if (!name) {
  console.error("usage: bun run revoke <device-name>")
  process.exit(1)
}
const store = new DeviceStore(DEVICES_FILE)
if (store.revoke(name)) {
  console.log(`Revoked "${name}".`)
} else {
  console.error(`No device named "${name}".`)
  process.exit(1)
}
