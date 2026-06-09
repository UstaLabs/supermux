import { readdirSync, statSync, readFileSync } from "fs"
import { join } from "path"
import { gzipSync } from "zlib"

const DIR = "../channels/web/static/assets"
const MAX_GZ = 250 * 1024     // 250KB target

let totalGz = 0
for (const f of readdirSync(DIR)) {
  if (!f.endsWith(".js")) continue
  const buf = readFileSync(join(DIR, f))
  const gz = gzipSync(buf).length
  console.log(`${f}: ${(buf.length / 1024).toFixed(1)}KB raw, ${(gz / 1024).toFixed(1)}KB gz`)
  totalGz += gz
}
console.log(`Total JS gz: ${(totalGz / 1024).toFixed(1)}KB`)
if (totalGz > MAX_GZ) {
  console.error(`Bundle exceeds ${MAX_GZ / 1024}KB target`)
  process.exit(1)
}
