import { createHash, randomBytes, timingSafeEqual } from "node:crypto"
import { chmod, mkdir, open, readFile } from "node:fs/promises"
import { isAbsolute, join, normalize, resolve } from "node:path"

export const SESSIOND_SECRET_FILE = "sessiond.secret"

function validateSecret(value: string): string {
  const canonical = /^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/.test(value)
  let decoded: Buffer
  try { decoded = Buffer.from(value, "base64") } catch { throw new Error("invalid sessiond secret encoding") }
  if (decoded.byteLength !== 32) throw new Error("invalid sessiond secret: expected 32 bytes")
  if (!canonical || decoded.toString("base64") !== value) throw new Error("invalid sessiond secret encoding")
  return value
}

async function readValidated(path: string): Promise<string> {
  return validateSecret(await readFile(path, "utf8"))
}

/** Load or exclusively create the user-scoped RPC secret. */
export async function createOrLoadSessiondSecret(stateDir: string): Promise<string> {
  await mkdir(stateDir, { recursive: true, mode: 0o700 })
  const path = join(stateDir, SESSIOND_SECRET_FILE)
  const value = randomBytes(32).toString("base64")
  try {
    const file = await open(path, "wx", 0o600)
    try {
      await file.writeFile(value, "utf8")
      await file.sync()
    } finally {
      await file.close()
    }
    return value
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code !== "EEXIST") throw error
    // A competing exclusive creator may have opened the file but not completed
    // its tiny write yet. Retry only empty/transient reads; stable bad data is
    // rejected rather than replaced.
    for (let attempt = 0; ; attempt++) {
      try {
        const loaded = await readValidated(path)
        if (process.platform !== "win32") await chmod(path, 0o600)
        return loaded
      } catch (readError) {
        const content = await readFile(path, "utf8").catch(() => "")
        if (attempt >= 20 || content.length >= 44) throw readError
        await new Promise(resolve => setTimeout(resolve, 5))
      }
    }
  }
}

export function timingSafeSecretEqual(expected: string, received: string): boolean {
  const left = Buffer.from(expected, "utf8")
  const right = Buffer.from(received, "utf8")
  const width = Math.max(left.length, right.length, 1)
  const paddedLeft = Buffer.alloc(width)
  const paddedRight = Buffer.alloc(width)
  left.copy(paddedLeft)
  right.copy(paddedRight)
  return timingSafeEqual(paddedLeft, paddedRight) && left.length === right.length
}

export function sessiondEndpoint(stateDir: string, platform: NodeJS.Platform = process.platform): string {
  const absolute = isAbsolute(stateDir) ? normalize(stateDir) : resolve(stateDir)
  if (platform !== "win32") return join(absolute, "sessiond.sock")
  const normalizedForWindows = absolute.replaceAll("/", "\\").toLowerCase()
  const hash = createHash("sha256").update(normalizedForWindows).digest("hex").slice(0, 32)
  return `\\\\.\\pipe\\supermux-sessiond-${hash}`
}
