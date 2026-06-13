import { readFileSync, writeFileSync } from "fs"
import { join } from "path"

const STAMP_FILE = "schema-version"

/**
 * Read the schema version stamp from STATE_DIR/schema-version.
 * Returns undefined if the file is missing or contains non-integer content.
 */
export function readSchemaStamp(stateDir: string): number | undefined {
  const path = join(stateDir, STAMP_FILE)
  let raw: string
  try {
    raw = readFileSync(path, "utf8").trim()
  } catch {
    return undefined
  }
  const n = Number(raw)
  if (!Number.isInteger(n) || raw === "") return undefined
  return n
}

/**
 * Write the schema version stamp to STATE_DIR/schema-version.
 * Writes `${value}\n` to the file.
 */
export function writeSchemaStamp(stateDir: string, value: number): void {
  const path = join(stateDir, STAMP_FILE)
  // Direct (non-atomic) write is fine here: a torn/partial write yields
  // unparseable content, which readSchemaStamp treats as missing → rewritten
  // on the next boot. No exec'd/handed-off file depends on it (unlike runtime-assets).
  writeFileSync(path, `${value}\n`, "utf8")
}

/**
 * Check the schema stamp against the number of migrations this build supports.
 * Returns ok:true when:
 *   - stamp file is missing (first boot or pre-Stage-2 upgrade)
 *   - stamp content is unparseable (treat as missing)
 *   - stamp <= supported (normal forward migration path)
 * Returns ok:false ONLY when stamp > supported (downgrade detected).
 */
export function checkSchemaStamp(
  stateDir: string,
  supported: number,
): { ok: true } | { ok: false; stamp: number } {
  const stamp = readSchemaStamp(stateDir)
  if (stamp === undefined) return { ok: true }
  if (stamp > supported) return { ok: false, stamp }
  return { ok: true }
}
