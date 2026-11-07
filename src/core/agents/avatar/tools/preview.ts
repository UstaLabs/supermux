// Browser-free contact sheet — the same one the broker's preview_avatar returns.
//   bun src/core/agents/avatar/tools/preview.ts avatar.json out.png
import { readFileSync, writeFileSync } from "node:fs"
import { checkAvatar, renderAvatarSheet } from ".."

const [src, out] = process.argv.slice(2)
if (!src || !out) { console.error("usage: render-native.ts avatar.json out.png"); process.exit(2) }
const check = checkAvatar(readFileSync(src, "utf8"))
console.log(check.errors.length ? "ERRORS:\n" + check.errors.join("\n") : "OK")
for (const w of check.warnings) console.log(w)
if (!check.spec) process.exit(1)
writeFileSync(out, renderAvatarSheet(check.spec))
console.log("wrote", out)
