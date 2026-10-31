/**
 * Antigravity (`agy`) driver: one long-lived `agy --input-format stream-json --output-format
 * stream-json` process per session behind the keeper, many turns per process. An interrupt is
 * SIGINT (agy ends the turn AND exits); the next prompt lazily respawns with `--conversation <id>`.
 * Model/permission changes restart an idle process the same way. agy runs under the session's
 * shadow HOME; the session instructions ride the first prompt of a new conversation.
 */
import { randomUUID } from "node:crypto"
import { existsSync, mkdirSync } from "node:fs"
import { join } from "node:path"
import { ANTIGRAVITY_CLI_DIR_ENV } from "../accounts/adapters/antigravity.js"
import { ANTIGRAVITY_CONTEXT, antigravityContextLaunch, appliedContext, sessionPreamble } from "../context/agents.js"
import { EMPTY_CONTEXT_FINGERPRINT } from "../context/index.js"
import { assertAccountCliDir, linkAntigravityCli, shareAntigravityHistory } from "../environment/antigravity.js"
import { CoreError, UnsupportedOperation } from "../errors.js"
import { detachedKeeperHooks, keeperAgentRunning } from "../keeper/client.js"
import { launchArgs, launchEnv } from "../launch.js"
import { appliedFor, launchPermissions, validatePermissionsSpec } from "../permissions.js"
import type { AgentDriver, AgentRuntime, CloseOptions, PermissionsSpec, SessionConfiguration } from "../types.js"
import { requireCloseMode } from "../types.js"
import { agyPromptBlocks, antigravityAttachments, loadAntigravityState, removeAntigravitySession, saveAntigravityState } from "./files.js"
import { createAntigravityNormalizer } from "./normalize.js"
import { agyTransport, type AgyExit, type AgyTransport } from "./transport.js"

export { createAntigravityNormalizer, type AntigravityNormalizer } from "./normalize.js"

export type AntigravityPermissions = Extract<PermissionsSpec, { kind: "antigravity" }>

export type AntigravityOptions = {
  id: string
  command: string
  /** Prefix argv before agy flags (tests: [fixture] with command: process.execPath). */
  args: string[]
  env?: Record<string, string>
  inheritEnv: boolean
  model?: string
  permissions: AntigravityPermissions
  /** shadow: the prepared shadow HOME; user: the real HOME (read only). */
  home: { shadow: string; user: string }
  setupTimeoutMs: number
  shutdownTimeoutMs: number
  maxFrameBytes: number
  /** How long an interrupted agy may take to end the turn after SIGINT before it is shut down. */
  interruptGraceMs: number
  keeper: {
    stateDirectory: string
    limits: { parkedDeadlineMs: number; journalMaxBytes: number; connectTimeoutMs: number }
  }
}

/**
 * supermux's own tools that only send an answer or a message: allowed in every mode. Headless agy cannot ask, so
 * in plan/default/accept-edits it denies every MCP call ("soft-denying tool confirmation CallMcpTool") unless a
 * `permissions.allow` rule names it (verified on agy 1.4.12, 2026-10-31). The server is supermux's mux-shim under
 * its shadow-config name.
 */
const SENDS_ONLY_TOOLS = ["mcp(supermux-mux-shim/council_report)", "mcp(supermux-mux-shim/message_session)"]

export function permissionFlags(spec: AntigravityPermissions): string[] {
  const allow = ["--settings", JSON.stringify({ permissions: { allow: SENDS_ONLY_TOOLS } })]
  switch (spec.mode) {
    case "bypass": return ["--dangerously-skip-permissions"]
    case "acceptEdits": return ["--mode", "accept-edits", ...allow]
    case "plan": return ["--mode", "plan", ...allow]
    default: return allow
  }
}

export function antigravityArgs(o: { prefix: string[]; conversationId?: string; model?: string; permissions: AntigravityPermissions }): string[] {
  return [
    ...o.prefix,
    "--input-format", "stream-json", "--output-format", "stream-json",
    ...(o.conversationId ? ["--conversation", o.conversationId] : []),
    ...(o.model ? ["--model", o.model] : []),
    ...permissionFlags(o.permissions),
  ]
}

type Deferred<T> = { promise: Promise<T>; resolve(value: T): void; reject(error: Error): void; settled: boolean }

function deferred<T>(): Deferred<T> {
  let resolve!: (value: T) => void, reject!: (error: Error) => void
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no })
  void promise.catch(() => {})
  const d: Deferred<T> = {
    promise,
    settled: false,
    resolve(value) { if (d.settled) return; d.settled = true; resolve(value) },
    reject(error) { if (d.settled) return; d.settled = true; reject(error) },
  }
  return d
}

function requireKeeper(keeper: AntigravityOptions["keeper"]): AntigravityOptions["keeper"] {
  if (!keeper || typeof keeper !== "object" || Array.isArray(keeper)) throw new TypeError("Antigravity keeper is required")
  if (typeof keeper.stateDirectory !== "string" || !keeper.stateDirectory) throw new TypeError("Antigravity keeper.stateDirectory must be a nonempty string")
  const limits = keeper.limits
  if (!limits || typeof limits !== "object" || Array.isArray(limits)) throw new TypeError("Antigravity keeper.limits is required")
  for (const key of ["parkedDeadlineMs", "journalMaxBytes", "connectTimeoutMs"] as const) {
    const v = limits[key]
    if (typeof v !== "number" || !Number.isSafeInteger(v) || v <= 0) throw new TypeError(`Antigravity keeper.limits.${key} must be a positive safe integer`)
  }
  return keeper
}

type DriverState = { conversationId: string; preambleSent: boolean }
type Proc = { t: AgyTransport; init: Deferred<string>; exited: boolean; epoch: string }
type Active = { id: string; completion: Deferred<{ stopReason: string }>; interrupted: boolean; proc?: Proc }

const identityMismatch = () => new CoreError("session_identity", "Antigravity session identity mismatch")
const conversationLost = (id: string, hint = "") => new CoreError(
  "conversation_lost",
  `Antigravity no longer has conversation ${id} (its history was deleted or it belongs to another account). Start a new session.${hint}`,
)
/** agy keeps every conversation (written at init, verified on agy 1.3.4) as <cli dir>/conversations/<id>.db. */
/**
 * True only when agy's conversations dir exists and lacks <id>.db. With no such dir (an unknown or
 * future layout) nothing is concluded: agy is launched and the init-mismatch guard decides.
 */
const conversationMissing = (cliDir: string, id: string) => {
  const dir = join(cliDir, "conversations")
  return existsSync(dir) && !existsSync(join(dir, `${id}.db`))
}

export function antigravity(options: AntigravityOptions): AgentDriver {
  if (!options || typeof options !== "object") throw new TypeError("Antigravity options are required")
  for (const field of ["id", "command", "args", "setupTimeoutMs", "shutdownTimeoutMs", "maxFrameBytes", "keeper", "inheritEnv", "permissions", "home"] as const) {
    if (options[field] === undefined) throw new TypeError(`Antigravity ${field} is required`)
  }
  const initialPermissions = validatePermissionsSpec(options.permissions)
  if (initialPermissions.kind !== "antigravity") throw new TypeError("Antigravity permissions kind must be antigravity")
  if (typeof options.id !== "string" || !options.id) throw new TypeError("Antigravity id is required")
  if (typeof options.command !== "string" || !options.command) throw new TypeError("Antigravity command is required")
  if (!Array.isArray(options.args) || options.args.some(value => typeof value !== "string")) throw new TypeError("Antigravity args is required")
  if (typeof options.inheritEnv !== "boolean") throw new TypeError("Antigravity inheritEnv is required")
  const home = options.home
  if (!home || typeof home !== "object" || typeof home.shadow !== "string" || !home.shadow || typeof home.user !== "string" || !home.user) {
    throw new TypeError("Antigravity home.shadow and home.user are required")
  }
  const { setupTimeoutMs, shutdownTimeoutMs, maxFrameBytes } = options
  if (options.interruptGraceMs === undefined) throw new TypeError("Antigravity interruptGraceMs is required")
  const interruptGraceMs = options.interruptGraceMs
  for (const value of [setupTimeoutMs, shutdownTimeoutMs, maxFrameBytes, interruptGraceMs]) if (!Number.isSafeInteger(value) || value <= 0) throw new TypeError("Antigravity limits must be positive safe integers")
  const keeper = requireKeeper(options.keeper)

  const keeperHooks = detachedKeeperHooks(keeper.stateDirectory, { shutdownTimeoutMs, connectTimeoutMs: keeper.limits.connectTimeoutMs })
  return { id: options.id, context: ANTIGRAVITY_CONTEXT, ...keeperHooks,
    /** The keeper's directory, then the session's own files here: its state and its attachments. */
    async forgetDetached(sessionId: string): Promise<boolean> {
      if (!(await keeperHooks.forgetDetached(sessionId))) return false
      await removeAntigravitySession(keeper.stateDirectory, sessionId)
      return true
    },
    async open(context): Promise<AgentRuntime> {
    context.signal.throwIfAborted()
    if (context.forkFrom) throw new UnsupportedOperation("fork", options.id)

    let model = context.configuration?.model ?? options.model
    let permissions: AntigravityPermissions = launchPermissions("antigravity", context.permissions, initialPermissions)
    const instructions = context.sessionContext ? appliedContext(context.sessionContext).instructions : undefined
    const normalizer = createAntigravityNormalizer()

    let agentSessionId = context.resumeId ?? ""
    let proc: Proc | undefined
    /** A retiring process: spawn() waits for it so connectKeeper never re-attaches a dying agent. */
    let stopping: Promise<void> | undefined
    /** A spawn in flight: close() waits for it and closes what it started. */
    let spawning: Promise<Proc> | undefined
    let active: Active | undefined
    /** The turn a re-attached process was running when the broker detached (no owner here). */
    let unowned: string | undefined
    let restartPending = false
    let closed = false
    let fatal: Error | undefined
    let state: DriverState = { conversationId: "", preambleSent: false }
    const attachments = () => antigravityAttachments(keeper.stateDirectory, context.sessionId)

    function saveState() {
      saveAntigravityState(keeper.stateDirectory, context.sessionId, JSON.stringify(state) + "\n")
    }
    function loadState(conversationId: string): DriverState {
      try {
        const text = loadAntigravityState(keeper.stateDirectory, context.sessionId)
        const parsed = text === undefined ? undefined : JSON.parse(text)
        if (parsed?.conversationId === conversationId && typeof parsed.preambleSent === "boolean") return { conversationId, preambleSent: parsed.preambleSent }
      } catch (error) {
        // A link (or another user's file) there is refused, never read through.
        if (error instanceof CoreError && error.code === "state_unsafe") throw error
        /* missing or unreadable: an adopted/older conversation */
      }
      // A conversation this driver did not create never gets the preamble again.
      return { conversationId, preambleSent: true }
    }

    function tail(p: Proc) {
      const text = p.t.stderrTail().trim()
      return text ? `: ${text.slice(-500)}` : ""
    }
    function completeUnowned() {
      if (!unowned) return
      const id = unowned
      unowned = undefined
      try { context.onActivity?.({ id, phase: "completed" }) } catch { /* */ }
    }
    function retire(p: Proc) {
      p.exited = true
      // The transport closes first, so onExit never reports this process: end its unowned turn here.
      if (proc === p) { proc = undefined; completeUnowned() }
      stopping = p.t.close({ mode: "shutdown" }).catch(() => {})
    }
    async function restartWhenIdle() {
      if (!proc || proc.exited) return
      // A re-attached process still running the detached broker's turn is busy too.
      if (active || unowned) { restartPending = true; return }
      retire(proc)
      await stopping
    }
    function failFatal(error: Error) {
      if (fatal) return
      fatal = error
      active?.completion.reject(error)
      if (!closed) context.onExit(error)
    }
    function meta(p: Proc) {
      return { agentSessionId, epoch: p.epoch, model: model ?? null, permissions }
    }

    function onMessage(p: Proc, frame: Record<string, any>) {
      if (proc !== p) return
      const cid = frame.conversation_id ?? frame.step_update?.conversation_id ?? frame.result?.conversation_id
      if (agentSessionId && typeof cid === "string" && cid && cid !== agentSessionId) {
        // agy did not have the conversation and started another one (stderr: `conversation "<id>" not found`).
        if (frame.event === "init") {
          const hint = /not found/i.test(p.t.stderrTail()) ? ` (agy: ${p.t.stderrTail().trim().slice(-300)})` : ""
          failFatal(conversationLost(agentSessionId, hint))
          retire(p)
        }
        return
      }
      context.onUpdate({ protocol: "native", value: frame })
      if (frame.event === "init" && typeof frame.conversation_id === "string" && frame.conversation_id) p.init.resolve(frame.conversation_id)
      if (frame.event !== "result") return
      const r = frame.result && typeof frame.result === "object" ? frame.result : {}
      const error = typeof r.error === "string" && r.error ? r.error : undefined
      if (!p.init.settled) p.init.reject(new Error(error ?? "Antigravity startup failed"))
      // agy always exits after SIGINT: never write to it again.
      if (error === "interrupted") retire(p)
      const a = active
      if (a && a.proc === p) {
        if (a.interrupted || error === "interrupted") a.completion.resolve({ stopReason: "cancelled" })
        else if (r.status === "SUCCESS") a.completion.resolve({ stopReason: "end_turn" })
        else a.completion.reject(new Error(error ?? "Antigravity turn failed"))
        p.t.setMeta({ ownedTurn: null })
      } else if (unowned) {
        completeUnowned()
        p.t.setMeta({ ownedTurn: null })
      }
      // configure/setPermissions while busy: applied after the turn.
      if (restartPending && !active && proc === p) { restartPending = false; retire(p) }
    }

    function onExit(p: Proc, exit: AgyExit) {
      if (exit.error?.message === "replaced") { failFatal(exit.error); return }
      p.exited = true
      p.init.reject(new Error(`Antigravity exited during setup${tail(p)}`))
      if (proc === p) proc = undefined
      const a = active
      if (a && a.proc === p) {
        if (a.interrupted) a.completion.resolve({ stopReason: "cancelled" })
        // Session flushes what the dead process left open (text, tool cards) when the turn ends.
        else a.completion.reject(new Error(`Antigravity exited before the turn finished${tail(p)}`))
      }
      completeUnowned()
    }

    /** The cli dir agy will use: an account's own (guarded, created, sharing history) or the user's. */
    function prepareCliDir(env: Record<string, string | undefined>): string {
      const userCli = join(home.user, ".gemini", "antigravity-cli")
      const accountCli = env[ANTIGRAVITY_CLI_DIR_ENV]
      if (!accountCli) return userCli
      // Checked before mkdir: a misconfigured value never creates anything under the real ~/.gemini.
      assertAccountCliDir(accountCli, home.user)
      mkdirSync(accountCli, { recursive: true, mode: 0o700 })
      shareAntigravityHistory(accountCli, userCli)
      return accountCli
    }

    function startSpawn(): Promise<Proc> {
      const s = spawn()
      spawning = s
      const clear = () => { if (spawning === s) spawning = undefined }
      s.then(clear, clear)
      return s
    }

    async function spawn(): Promise<Proc> {
      await stopping
      const env = launchEnv(options.inheritEnv, options.env, context.profile)
      const cliDir = prepareCliDir(env)
      // Never launch --conversation for a conversation agy lacks: it would start (and orphan) a new one.
      if (agentSessionId && conversationMissing(cliDir, agentSessionId) &&!(await keeperAgentRunning(keeper.stateDirectory, context.sessionId))) {
        const error = conversationLost(agentSessionId)
        failFatal(error)
        throw error
      }
      linkAntigravityCli(home.shadow, cliDir, home.user)
      const launch = antigravityContextLaunch(context.sessionContext, { shadowHome: home.shadow, userHome: home.user })
      normalizer.setServerAliases(launch.serverAliases)
      env.HOME = home.shadow
      if (process.platform === "win32") env.USERPROFILE = home.shadow
      const epoch = Date.now().toString(36)
      normalizer.newGeneration(epoch)
      let p!: Proc
      const t = await agyTransport({
        command: options.command,
        args: launchArgs(antigravityArgs({ prefix: options.args, conversationId: agentSessionId || undefined, model, permissions }), context.profile),
        env, cwd: context.cwd, shutdownTimeoutMs, maxFrameBytes,
        sessionId: context.sessionId, keeper,
        fingerprint: context.sessionContext?.fingerprint ?? EMPTY_CONTEXT_FINGERPRINT,
      }, frame => { if (p) onMessage(p, frame) }, exit => { if (p) onExit(p, exit) })
      p = { t, init: deferred<string>(), exited: false, epoch }
      proc = p
      const welcomeMeta = t.welcome.meta ?? {}
      const reattached = t.welcome.agentRunning === true && typeof welcomeMeta.agentSessionId === "string"
      if (reattached) {
        const sid = welcomeMeta.agentSessionId as string
        if (agentSessionId && agentSessionId !== sid) {
          p.exited = true
          proc = undefined
          await t.close({ mode: "shutdown" }).catch(() => {})
          throw identityMismatch()
        }
        agentSessionId = sid
        p.init.resolve(sid)
        // Keep the ids of the running process stable across the re-attach.
        if (typeof welcomeMeta.epoch === "string") { p.epoch = welcomeMeta.epoch; normalizer.newGeneration(welcomeMeta.epoch) }
        // What you asked for is what runs: a process launched with other settings restarts at the next prompt.
        if ((welcomeMeta.model ?? null) !== (model ?? null) || JSON.stringify(welcomeMeta.permissions) !== JSON.stringify(permissions)) restartPending = true
        const owned = welcomeMeta.ownedTurn as { id?: unknown } | null | undefined
        if (owned && typeof owned === "object" && typeof owned.id === "string") {
          unowned = owned.id
          try { context.onActivity?.({ id: owned.id, phase: "started" }) } catch { /* */ }
        }
      } else if (agentSessionId) {
        t.setMeta(meta(p))
      }
      return p
    }

    /** SIGINT; an agy that has not ended the turn (or exited) within the grace period is shut down. */
    function sigint(p: Proc, stillRunning: () => boolean, onForced: () => void) {
      p.t.interrupt()
      const timer = setTimeout(() => {
        if (p.exited || proc !== p || !stillRunning()) return
        retire(p)
        onForced()
      }, interruptGraceMs)
      timer.unref?.()
    }

    async function interrupt() {
      const a = active
      if (!a) {
        const id = unowned
        if (id && proc && !proc.exited) sigint(proc, () => unowned === id, () => {})
        return
      }
      a.interrupted = true
      if (!a.proc || !proc || proc !== a.proc || proc.exited) { a.completion.resolve({ stopReason: "cancelled" }); return }
      // Completion comes from result{error:"interrupted"} (or onExit when agy dies without one).
      sigint(proc, () => !a.completion.settled, () => a.completion.resolve({ stopReason: "cancelled" }))
    }

    async function close(closeOptions: CloseOptions) {
      const mode = requireCloseMode(closeOptions)
      if (!closed) { closed = true; active?.completion.reject(new CoreError("runtime_closed", "Antigravity runtime closed")) }
      const pending = spawning
      const p = proc
      proc = undefined
      await stopping
      if (p) await p.t.close({ mode })
      // A spawn still in flight would otherwise leave its agy running with nobody to close it.
      const late = pending ? await pending.catch(() => undefined) : undefined
      if (late && late !== p) {
        late.exited = true
        if (proc === late) proc = undefined
        await late.t.close({ mode })
      }
    }

    if (!context.resumeId) {
      // Design A (probed on agy 1.3.4): init names the new conversation before any input.
      const p = await startSpawn()
      let timer: ReturnType<typeof setTimeout> | undefined
      const onAbort = () => p.init.reject(new Error("Antigravity setup aborted"))
      try {
        context.signal.addEventListener("abort", onAbort, { once: true })
        if (context.signal.aborted) onAbort()
        timer = setTimeout(() => p.init.reject(new Error("Antigravity setup timed out")), setupTimeoutMs)
        const id = await p.init.promise
        if (fatal) throw fatal
        agentSessionId = id
        state = { conversationId: id, preambleSent: false }
        saveState()
        p.t.setMeta(meta(p))
      } catch (error) {
        await close({ mode: "shutdown" })
        throw error
      } finally {
        clearTimeout(timer)
        context.signal.removeEventListener("abort", onAbort)
      }
    } else {
      agentSessionId = context.resumeId
      state = loadState(context.resumeId)
      saveState()
      // Re-attach a detached process (or replace one launched for another context); else lazy.
      if (await keeperAgentRunning(keeper.stateDirectory, context.sessionId)) await startSpawn()
      else if (conversationMissing(prepareCliDir(launchEnv(options.inheritEnv, options.env, context.profile)), context.resumeId)) {
        throw conversationLost(context.resumeId)
      }
    }

    return {
      agentSessionId,
      capabilities: { resume: true, steer: false, fork: false, detach: true, configure: true, permissions: true },
      nativeProtocol: "antigravity-stream-json",
      async prompt(content, signal) {
        if (fatal || closed) throw fatal ?? new CoreError("runtime_closed", "Antigravity runtime closed")
        signal.throwIfAborted()
        if (active) throw new CoreError("session_busy", "Antigravity prompt already running")
        const blocks = agyPromptBlocks(content, attachments)
        if (!state.preambleSent && instructions?.trim()) blocks.unshift({ type: "text", text: sessionPreamble(instructions) })
        const a: Active = { id: randomUUID(), completion: deferred<{ stopReason: string }>(), interrupted: false }
        active = a
        const abort = () => { void interrupt() }
        signal.addEventListener("abort", abort, { once: true })
        try {
          if (restartPending && proc && !proc.exited) retire(proc)
          restartPending = false
          const fresh = !proc || proc.exited
          const p = fresh ? await startSpawn() : proc!
          if (a.interrupted) return { stopReason: "cancelled" } // interrupted while spawning: nothing was sent
          if (fatal || closed) throw fatal ?? new CoreError("runtime_closed", "Antigravity runtime closed")
          if (fresh) {
            // The prompt goes only to a process whose init named this conversation (onMessage checks it).
            let timer: ReturnType<typeof setTimeout> | undefined
            try {
              const cancelled = await Promise.race([
                p.init.promise.then(() => undefined),
                a.completion.promise,
                new Promise<never>((_, reject) => { timer = setTimeout(() => reject(new Error("Antigravity setup timed out")), setupTimeoutMs) }),
              ])
              if (cancelled) return cancelled // interrupted while agy started: nothing was sent
            } catch (error) {
              if (proc === p) retire(p)
              throw error
            } finally { clearTimeout(timer) }
            if (fatal || closed) throw fatal ?? new CoreError("runtime_closed", "Antigravity runtime closed")
          }
          a.proc = p
          p.t.setMeta({ ownedTurn: { id: a.id } })
          p.t.write({ event: "user", message: { role: "user", content: blocks } })
          // Also after an interrupted first turn: agy has the text.
          if (!state.preambleSent) { state.preambleSent = true; saveState() }
          return await a.completion.promise
        } finally {
          signal.removeEventListener("abort", abort)
          if (active === a) active = undefined
          if (restartPending && proc && !proc.exited && !active) { restartPending = false; retire(proc) }
        }
      },
      interrupt,
      close,
      async configure(next: SessionConfiguration) {
        if (fatal || closed) throw fatal ?? new CoreError("runtime_closed", "Antigravity runtime closed")
        // reasoningEffort is ignored: agy's effort is part of the model id.
        const wanted = next.model ?? options.model
        if (wanted === model) return
        model = wanted
        await restartWhenIdle()
      },
      configuration() { return model ? { model } : {} },
      async setPermissions(spec) {
        if (fatal || closed) throw fatal ?? new CoreError("runtime_closed", "Antigravity runtime closed")
        const next = validatePermissionsSpec(spec)
        if (next.kind !== "antigravity") throw new TypeError("Antigravity permissions kind must be antigravity")
        if (JSON.stringify(next) !== JSON.stringify(permissions)) {
          permissions = next
          await restartWhenIdle()
        }
        return { applied: appliedFor(next) }
      },
      normalize: normalizer,
      flush: () => normalizer.flush(),
    }
  } }
}
