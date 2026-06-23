// Test harness: seal a supermux push payload for a device pubkey and emit a
// ready-to-`simctl push` APNs file. NOT shipped — used to verify the iOS NSE decrypt.
//
//   bun scripts/seal-test-push.ts <pubB64url> [outFile]
//
// Builds `{ aps:{ alert:{title,body}, "mutable-content":1 }, data:"<sealed blob>" }`
// (mirrors src/relay/apns.ts) sealed to <pubB64url> via core/push sealForDevice.
import { sealForDevice } from "../src/core/push/encrypt"

const pub = process.argv[2]
const outFile = process.argv[3] ?? "/tmp/payload.apns"
if (!pub) {
  console.error("usage: bun scripts/seal-test-push.ts <pubB64url> [outFile]")
  process.exit(1)
}

const payload = {
  session: "travel-assistant",
  text: "Agent finished ✅",
  ts: new Date().toISOString(),
}

const blob = await sealForDevice(pub, JSON.stringify(payload))
const apns = {
  aps: { alert: { title: "supermux", body: "" }, "mutable-content": 1 },
  data: blob,
}
await Bun.write(outFile, JSON.stringify(apns, null, 2))
console.error(`wrote ${outFile} (blob ${blob.length} chars) for pub ${pub.slice(0, 16)}…`)
console.log(outFile)
