import { createHash } from 'node:crypto'
import { tmpdir } from 'node:os'
import { join, resolve } from 'node:path'
import { SUN_PATH_MAX, socketCandidates } from '../unix-socket.js'

/**
 * Where a new keeper for keeper directory `dir` may listen, best first: `<dir>/keeper.sock` when that
 * fits sun_path (Node can neither bind nor connect a longer one); otherwise
 * `<base>/supermux-keepers-<uid>/<hash of dir>.sock` for each base that keeps it within sun_path:
 * the OS temp dir, `/tmp`, then `$XDG_RUNTIME_DIR` (when set, absolute and present) last, since it is
 * deleted at logout without lingering: a detached keeper would lose its socket, and a resume would
 * start a second agent on the session (see socketCandidates). The private 0700 directory, checked
 * whenever a recorded socket is used, keeps other users out of the shared temp dir. The process
 * starting the keeper takes the first whose directory it can make private, and the keeper records it
 * in its status, so every later client finds it whatever its own environment.
 */
export function keeperSocketPaths(dir: string, env: NodeJS.ProcessEnv = process.env, tmp: string = tmpdir()): string[] {
  const name = createHash('sha256').update(resolve(dir)).digest('hex').slice(0, 16)
  const paths = socketCandidates(join(dir, 'keeper.sock'), 'supermux-keepers', `${name}.sock`, env, tmp)
  if (!paths.length) throw new Error(`No usable keeper socket path for ${dir}: every candidate is longer than ${SUN_PATH_MAX} bytes`)
  return paths
}
