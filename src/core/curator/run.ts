import { readFileSync } from "fs"
import { makeLogger } from "../../shared/log"

const log = makeLogger("curator/run")

export type CuratorDeps = {
  chatId: string
  repoPath: string
  promptPath: string
  /** Spawn a claude session; returns its registry name. */
  spawn: (args: { workdir: string; name: string }) => Promise<{ name: string }>
  /** Wait until the named session is CONNECTED (shim attached); resolve its session id (or undefined on timeout). */
  waitReady: (name: string) => Promise<string | undefined>
  /** Deliver the task prompt to the session, tagged for the given chat. */
  sendInbound: (sessionId: string, content: string, chatId: string) => Promise<void>
  /** True when the session's agent state is idle. */
  isIdle: (sessionId: string) => boolean
  getActive: (chatId: string) => string | undefined
  setActive: (chatId: string, name: string) => void
  /** Archive (terminate) the curator session by name. */
  archive: (name: string) => void
  /** Post a plain notice into the chat (used for error surfacing). */
  postNotice: (chatId: string, text: string) => Promise<void>
  sleep?: (ms: number) => Promise<void>
}

let running = false

const defaultSleep = (ms: number) => new Promise<void>((r) => setTimeout(r, ms))

/**
 * Wait for the curator to finish its turn: it must go active (received the
 * task) and then return to idle. A 20-minute ceiling backstops a hung run.
 * Curator runs take minutes (dump → read → edit → git → reply), so the
 * "active across a 5s poll" assumption is safe.
 */
/**
 * Deliver the task and confirm the agent actually picks it up. A freshly
 * spawned claude session has TWO shims (tools + channel) connecting in a churn;
 * delivering during that churn loses the frame (it lands on the tools shim or a
 * socket being replaced, and isn't queued). So: wait a settle grace after the
 * session reports connected, then send and watch the agent state — if it
 * doesn't leave idle within the window, resend. Returns true once the agent is
 * active. (Resends are rare given the grace, so duplicate delivery is unlikely;
 * if it happens the curator simply re-runs idempotently.)
 */
async function deliverUntilActive(
  deps: CuratorDeps,
  sid: string,
  prompt: string,
  opts: { graceMs?: number; attempts?: number; windowMs?: number; pollMs?: number } = {},
): Promise<boolean> {
  const sleep = deps.sleep ?? defaultSleep
  const graceMs = opts.graceMs ?? 8_000
  const attempts = opts.attempts ?? 5
  const windowMs = opts.windowMs ?? 15_000
  const pollMs = opts.pollMs ?? 2_000

  const pollsPerAttempt = Math.max(1, Math.ceil(windowMs / pollMs))
  await sleep(graceMs)
  for (let attempt = 1; attempt <= attempts; attempt++) {
    await deps.sendInbound(sid, prompt, deps.chatId)
    log.info("curator_inbound_sent", { sid, attempt })
    for (let p = 0; p < pollsPerAttempt; p++) {
      await sleep(pollMs)
      if (!deps.isIdle(sid)) {
        log.info("curator_active", { sid, attempt })
        return true
      }
    }
  }
  return false
}

async function waitForCompletion(
  deps: CuratorDeps,
  sid: string,
  opts: { maxMs?: number; pollMs?: number } = {},
): Promise<void> {
  const sleep = deps.sleep ?? defaultSleep
  const maxMs = opts.maxMs ?? 20 * 60_000
  const pollMs = opts.pollMs ?? 5_000
  const maxPolls = Math.max(1, Math.ceil(maxMs / pollMs))
  let sawActive = false
  for (let p = 0; p < maxPolls; p++) {
    await sleep(pollMs)
    if (!deps.isIdle(sid)) sawActive = true
    else if (sawActive) return
  }
  log.warn("curator_completion_timeout", { sid })
}

/**
 * Run one curation pass: spawn a curator session bound to the chat, deliver the
 * task, wait for it to finish, then always archive the curator and restore the
 * chat's previously-active session. Re-entrancy guarded; never throws.
 */
export async function runCurator(deps: CuratorDeps): Promise<void> {
  if (running) {
    log.info("curator_skip_already_running")
    return
  }
  running = true
  const prevActive = deps.getActive(deps.chatId)
  let curatorName: string | undefined
  try {
    const prompt = readFileSync(deps.promptPath, "utf8")
    const { name } = await deps.spawn({ workdir: deps.repoPath, name: "nightly-curator" })
    curatorName = name
    const sid = await deps.waitReady(name)
    if (!sid) throw new Error("curator session did not become ready in time")
    deps.setActive(deps.chatId, name) // route the curator's replies into the chat
    const active = await deliverUntilActive(deps, sid, prompt)
    if (!active) throw new Error("curator did not start after delivery retries")
    await waitForCompletion(deps, sid)
    log.info("curator_run_complete", { name })
  } catch (err: any) {
    const msg = err?.message ?? String(err)
    log.warn("curator_run_error", { err: msg })
    await deps.postNotice(deps.chatId, `🌙 Nightly curator failed: ${msg}`.slice(0, 200)).catch(() => {})
  } finally {
    if (curatorName) {
      try {
        deps.archive(curatorName)
      } catch (err: any) {
        log.warn("curator_archive_failed", { err: err?.message ?? String(err) })
      }
    }
    if (prevActive) {
      try {
        deps.setActive(deps.chatId, prevActive)
      } catch {
        /* best effort */
      }
    }
    running = false
  }
}

export { waitForCompletion, deliverUntilActive }
