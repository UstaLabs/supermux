import { CoreError } from '../errors.js'
import type { KeeperExitStatus } from './client.js'

/** Windows reports a crash as an NTSTATUS code (0xC0000005 is an access violation): those read better in hex. */
export function exitCodeText(code: number): string {
  const unsigned = code >>> 0
  return unsigned > 0xffff ? `0x${unsigned.toString(16).toUpperCase()}` : String(code)
}

/**
 * The error for an agent whose output ended while its session was open: how the process exited (its code or the
 * signal) and the last line it wrote to stderr, or that its keeper went away without saying. Code `agent_exited`;
 * `exit` carries what the keeper reported (absent when it reported nothing).
 */
export function agentExitError(agent: string, exit: KeeperExitStatus | undefined): CoreError & { exit?: KeeperExitStatus } {
  const how = !exit ? ': its keeper stopped without reporting how'
    : exit.signal ? ` on ${exit.signal}`
    : exit.code === null ? '' : ` with code ${exitCodeText(exit.code)}`
  const last = exit?.stderr.at(-1)?.trim()
  const error = new CoreError('agent_exited', `${agent} process exited${how}${last ? `: ${last}` : ''}`)
  return exit ? Object.assign(error, { exit }) : error
}
