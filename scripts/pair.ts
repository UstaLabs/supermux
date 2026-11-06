import { readFileSync } from "fs"
import qrcode from "qrcode-terminal"
import { DeviceStore } from "../src/channels/web/device-store"
import { DEVICES_FILE, ENV_FILE, HOST_KEY_FILE } from "../src/shared/paths"
import { readEnvText } from "../src/shared/env-file"
import { loadOrCreateHostKey } from "../src/core/host-identity"

const name = process.argv[2]
if (!name) {
  console.error("usage: bun run pair <device-name>")
  process.exit(1)
}

// load .env to find MUX_WEB_PUBLIC_URL
try {
  for (const [key, value] of readEnvText(readFileSync(ENV_FILE, "utf8"))) {
    if (process.env[key] === undefined) process.env[key] = value
  }
} catch {}

const relayDomain = process.env.MUX_RELAY_DOMAIN?.trim()
const publicUrl = relayDomain
  ? `https://h-${loadOrCreateHostKey(HOST_KEY_FILE).hostId}.${relayDomain}`
  : process.env.MUX_WEB_PUBLIC_URL
if (!publicUrl) {
  console.error("MUX_WEB_PUBLIC_URL not set in", ENV_FILE)
  process.exit(1)
}

const store = new DeviceStore(DEVICES_FILE)
const { token, name: finalName } = store.mint(name)

const url = `${publicUrl.replace(/\/$/, "")}/pair?t=${token}`

console.log(`\nPaired "${finalName}".`)
console.log(`\nOpen this URL on ${finalName} (or scan the QR):\n`)
console.log(`  ${url}\n`)
qrcode.generate(url, { small: true })
