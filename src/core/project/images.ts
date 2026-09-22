import { randomUUID } from "crypto"
import { mkdirSync, writeFileSync, renameSync, rmSync } from "fs"
import { join } from "path"

const EXT: Record<string, string> = { "image/png": "png", "image/jpeg": "jpg", "image/webp": "webp", "image/gif": "gif" }
const MIME: Record<string, string> = Object.fromEntries(Object.entries(EXT).map(([m, e]) => [e, m]))
const IMAGE_ID_RE = /^[0-9a-f-]{36}\.(png|jpg|webp|gif)$/

export const PROJECT_IMAGE_MAX_BYTES = 5 * 1024 * 1024

/**
 * Durable project image files. The directory is `<STATE_DIR>/project-images`, deliberately
 * NOT under the chat-upload store, so upload cleanup never touches it (spec: images must
 * survive temporary-upload cleanup). An image id is `<uuid>.<ext>`; the id is the whole
 * file name, and `path` rejects anything else so an id can never escape the directory.
 */
export class ProjectImages {
  constructor(private readonly dir: string) {}

  isSupported(mime: string): boolean {
    return mime in EXT
  }

  /** Atomic: written to a tmp file in the same directory, then renamed into place. */
  write(bytes: Uint8Array, mime: string): string {
    const ext = EXT[mime]
    if (!ext) throw new Error(`unsupported image type: ${mime}`)
    mkdirSync(this.dir, { recursive: true, mode: 0o700 })
    const id = `${randomUUID()}.${ext}`
    const tmp = join(this.dir, `.${id}.tmp`)
    try {
      writeFileSync(tmp, bytes, { mode: 0o600 })
      renameSync(tmp, join(this.dir, id))
    } catch (e) {
      rmSync(tmp, { force: true })
      throw e
    }
    return id
  }

  path(imageId: string): string | undefined {
    return IMAGE_ID_RE.test(imageId) ? join(this.dir, imageId) : undefined
  }

  mimeOf(imageId: string): string {
    return MIME[imageId.slice(imageId.lastIndexOf(".") + 1)] ?? "application/octet-stream"
  }

  /** Best effort: a missing file or an invalid id is not an error. */
  remove(imageId: string): void {
    const p = this.path(imageId)
    if (!p) return
    try { rmSync(p, { force: true }) } catch {}
  }
}
