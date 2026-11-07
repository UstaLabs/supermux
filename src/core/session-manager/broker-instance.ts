// One broker per state directory (#36). The kernel lock comes first: a second broker stops right here, before it
// reads or writes broker.pid, opens .core.lock or binds the port, so nothing the first broker holds is disturbed.
// broker.pid stays for what reads it (the desktop app, `supermux status`); the lock is what decides.
import { lock } from "@supermux/platform"
import { acquirePidFile, releasePidFile } from "./pid-file"

export interface BrokerClaim {
  /** Frees broker.pid (if it still names this process) and the lock. */
  release(): Promise<void>
}

/** Claims [stateDir] for this broker: its lock, then [pidFile]. Rejects with `already_running` while another holds it. */
export async function claimBroker(stateDir: string, pidFile: string): Promise<BrokerClaim> {
  const instance = await lock.singleInstance("broker", stateDir)
  try {
    acquirePidFile(pidFile)
  } catch (error) {
    await instance.release()
    throw error
  }
  return {
    async release() {
      releasePidFile(pidFile)
      await instance.release()
    },
  }
}
