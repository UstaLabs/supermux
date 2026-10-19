import { createHash } from 'node:crypto'
import { tmpdir } from 'node:os'
import { join, resolve } from 'node:path'
import { SUN_PATH_MAX } from '../unix-socket.js'

/**
 * Where a new keeper for keeper directory `dir` listens: `<dir>/keeper.sock` when that fits sun_path
 * (Node can neither bind nor connect a longer one), otherwise `<os temp dir>/supermux-keepers-<uid>/<hash of dir>.sock`,
 * in a private directory per user. The process that starts the keeper picks it and the keeper records it in its
 * status (`KeeperStatus.socket`), so every later client finds it whatever its own temp dir is.
 */
export function keeperSocketPath(dir: string): string {
  const inside = join(dir, 'keeper.sock')
  if (Buffer.byteLength(inside) <= SUN_PATH_MAX) return inside
  const user = typeof process.getuid === 'function' ? `-${process.getuid()}` : ''
  const name = createHash('sha256').update(resolve(dir)).digest('hex').slice(0, 16)
  const path = join(tmpdir(), `supermux-keepers${user}`, `${name}.sock`)
  if (Buffer.byteLength(path) > SUN_PATH_MAX) throw new Error(`No usable keeper socket path: ${path} is longer than ${SUN_PATH_MAX} bytes`)
  return path
}
