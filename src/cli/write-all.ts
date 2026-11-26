import { writeSync } from "node:fs"

/**
 * Write `text` to file descriptor `fd` completely before returning, so a process.exit() that follows loses nothing.
 * A reader that has gone away (`| head -1`: EPIPE) ends the writing quietly, as a shell tool should.
 */
export function writeAll(fd: number, text: string): void {
  const buf = Buffer.from(text)
  let off = 0
  while (off < buf.length) {
    try {
      off += writeSync(fd, buf, off)
    } catch (e) {
      const code = (e as NodeJS.ErrnoException).code
      if (code === "EAGAIN") { Bun.sleepSync(1); continue }
      if (code === "EPIPE") return
      throw e
    }
  }
}
