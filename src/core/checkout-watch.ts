// A broker run from a checkout keeps the code it started with, while what it starts later (keepers, sessions) loads the
// files on disk. When the checkout moves under it (a fast-forward), that mix ran unnoticed: the live broker's keepers once
// died at an import the new code needed. This says so, once in the log and on /host for the apps, and refuses nothing,
// so sessions keep starting (the keeper-error card; a warning, never a refusal).

/** What /host carries while the checkout has moved: the short commits, and the sentence the apps show. */
export interface CheckoutMoved {
  runs: string
  checkout: string
  message: string
}

export interface CheckoutWatchOptions {
  /** The checkout's HEAD commit; null (or a throw) when it cannot be read. */
  readHead: () => Promise<string | null>
  log: (event: string, fields: Record<string, string>) => void
  /** How often [check] runs once started; 0 (the default) runs it only when called. */
  intervalMs?: number
}

export interface CheckoutWatch {
  /** Reads the HEAD the broker started from (nothing is ever claimed when it cannot), then checks every intervalMs. */
  start(): Promise<void>
  check(): Promise<void>
  /** The latest answer; undefined while the checkout is where the broker started. */
  current(): CheckoutMoved | undefined
  stop(): void
}

const short = (commit: string) => commit.slice(0, 9)

export function createCheckoutWatch(options: CheckoutWatchOptions): CheckoutWatch {
  let started: string | null = null
  let moved: CheckoutMoved | undefined
  let logged: string | undefined
  let timer: ReturnType<typeof setInterval> | undefined
  const read = () => options.readHead().catch(() => null)

  async function check(): Promise<void> {
    if (!started) return
    const now = await read()
    // Unreadable now (git busy, the checkout mid-update): the last answer stands.
    if (!now) return
    if (now === started) {
      moved = undefined
      logged = undefined
      return
    }
    moved = {
      runs: short(started),
      checkout: short(now),
      message: `This broker runs ${short(started)}, the checkout is at ${short(now)}: restart it to run the new code.`,
    }
    if (logged !== now) {
      logged = now
      options.log("checkout_moved", { runs: started, checkout: now })
    }
  }

  return {
    async start() {
      started = await read()
      if (options.intervalMs) {
        timer = setInterval(() => void check(), options.intervalMs)
        ;(timer as { unref?: () => void }).unref?.()
      }
    },
    check,
    current: () => moved,
    stop() {
      if (timer) clearInterval(timer)
      timer = undefined
    },
  }
}
