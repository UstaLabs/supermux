// Owning the zmx helper process from the broker side.
//
// ONE HELPER PROCESS IS ONE VIEWER. It is started per attachment, it speaks
// the five-byte envelope in ./protocol.ts on stdin/stdout, and it dies with
// the viewer:
//
//   * `close()`/`kill()` DETACH the viewer. The target keeps running — the
//     shell belongs to a zmx daemon that outlives every broker process.
//   * Destroying a target is the explicit `kill` COMMAND, which is a message
//     to the daemon and has nothing to do with this process's lifetime.
//   * A helper that dies for any other reason is a `backend-unavailable`
//     failure, recoverable by re-attaching. It is NEVER reported as the
//     target exiting: only a `exit` event, which the daemon only sends with a
//     real waitpid status behind it, may close a tab.
//
// Everything crossing this boundary is verified before it is trusted: the
// binaries against a manifest written at build time, and the running
// process's ABI against ours, from its first frame.
import { createHash } from "crypto"
import { existsSync, readFileSync, statSync } from "fs"
import { join, resolve as resolvePath } from "path"
import { IS_COMPILED } from "../../../shared/build-info"
import { STATE_DIR } from "../../../shared/paths"
import { zmxBundleDir } from "../../runtime-assets"
import { WorkspaceTerminalError } from "../workspace-backend"
import {
  DETACH_REASONS,
  FrameDecoder,
  HELPER_PROTOCOL_VERSION,
  HelperProtocolError,
  asEvent,
  encodeControl,
  encodeInput,
  encodeReply,
  outputChunks,
  type HelperCommandBody,
  type HelperEvent,
} from "./protocol"

/** The helper ABI this build speaks. Must equal the helper's `--version-json`. */
export const HELPER_ABI = HELPER_PROTOCOL_VERSION

/** Keep the last 16 KiB of the helper's stderr for diagnostics, and no more. */
const STDERR_LIMIT = 16 * 1024

/** How long `close()` waits for a clean detach before killing the process. */
const CLOSE_TIMEOUT_MS = 2000

/**
 * How long a command waits for its ack before the helper is declared wedged.
 *
 * A helper that is ALIVE but not answering is the case neither the exit
 * watcher nor the stdout reader can see: both are waiting on a pipe that is
 * simply quiet. Without this an `ensure` or a `focus` awaits forever and takes
 * the caller's request with it. The budget is generous because `create` waits
 * on a zmx session appearing, which the helper itself bounds at 10s.
 */
export const SEND_TIMEOUT_MS = 20_000

export type HelperManifest = {
  schema: number
  /** Protocol version the helper binary was built to speak. */
  abi: number
  target: string
  builtAt?: string
  helper: { sha256: string }
  zmx: { commit: string; sha256: string }
  patch: { sha256: string }
}

export type HelperBinaries = {
  dir: string
  /** The helper we exec. */
  helper: string
  /** The patched zmx the helper runs to CREATE a target. */
  zmx: string
  manifest: string
}

/**
 * Where the built helper and its patched zmx live.
 *
 *  1. `MUX_ZMX_BIN_DIR` — explicit override. This is how a DESKTOP package says
 *     "use the bundle I own": the app materializes its own copy out of the app
 *     image and names the directory, rather than leaving the broker to guess
 *     (see HostBinaries.kt). Tests and odd deployments use it too.
 *  2. the repo's gitignored build cache, in source mode.
 *  3. `<stateDir>/runtime-assets/<version>/zmx` when compiled — the embedded
 *     bundle, copied out on first use. Materialising it is a packaging step, not
 *     an optimization: these are native binaries a child process must be able to
 *     exec, and a $bunfs path is not something the kernel can exec.
 *
 * A compiled binary that was built without the bundle (SUPERMUX_SKIP_ZMX=1)
 * materializes the committed placeholder, whose manifest says so — which is why
 * `verifyHelperManifest` answers "this build ships no zmx bundle" instead of a
 * hash mismatch nobody can act on.
 */
export function zmxBinDir(env: NodeJS.ProcessEnv = process.env, stateDir: string = STATE_DIR): string {
  const override = env.MUX_ZMX_BIN_DIR?.trim()
  if (override) return override
  // src/core/terminal/zmx/helper.ts -> repo root is four levels up.
  const repoBuild = resolvePath(import.meta.dirname, "..", "..", "..", "..", "build", "zmx", "out")
  if (existsSync(join(repoBuild, "bin", "mux-zmx-helper"))) return repoBuild
  if (IS_COMPILED) return zmxBundleDir(stateDir)
  return join(stateDir, "runtime-assets", "zmx")
}

export function helperBinaries(dir: string = zmxBinDir()): HelperBinaries {
  return {
    dir,
    helper: join(dir, "bin", "mux-zmx-helper"),
    zmx: join(dir, "bin", "zmx"),
    manifest: join(dir, "manifest.json"),
  }
}

// Digesting ~100 MB of binaries on every attach would be absurd; digesting
// them never would make the manifest decoration. Cache per path+size+mtime,
// which changes on any rebuild or replacement.
const digestCache = new Map<string, string>()

function fileDigest(path: string): string {
  const stat = statSync(path)
  const key = `${path}:${stat.size}:${stat.mtimeMs}`
  const cached = digestCache.get(key)
  if (cached) return cached
  const digest = createHash("sha256").update(readFileSync(path)).digest("hex")
  digestCache.set(key, digest)
  return digest
}

/**
 * Read the manifest and prove the binaries on disk are the ones it describes.
 *
 * The manifest is written by `scripts/build-zmx.sh` beside the binaries and
 * names the zmx commit and the patch hash they were built from. A helper whose
 * bytes do not match it is not the helper we tested the protocol against, and
 * the wire between us has no other version handshake until the process is
 * already running.
 */
export function verifyHelperManifest(binaries: HelperBinaries = helperBinaries()): HelperManifest {
  const fail = (message: string): never => {
    throw new WorkspaceTerminalError("backend-unavailable", message, false)
  }
  if (!existsSync(binaries.manifest)) {
    return fail(`zmx helper manifest missing at ${binaries.manifest} (run scripts/build-zmx.sh --helper)`)
  }
  let manifest: HelperManifest
  try {
    manifest = JSON.parse(readFileSync(binaries.manifest, "utf8")) as HelperManifest
  } catch (error) {
    return fail(`zmx helper manifest is unreadable: ${errorText(error)}`)
  }
  // The committed slot, materialized verbatim. Saying "schema 0" here would be
  // true and useless: the actionable fact is that this BUILD carries no bundle,
  // and no amount of rebuilding zmx on the host will change that.
  if ((manifest as { placeholder?: boolean }).placeholder) {
    return fail(
      `this build ships no zmx bundle (${binaries.manifest} is the placeholder slot) — ` +
        "it was compiled with SUPERMUX_SKIP_ZMX=1, so POSIX workspace terminals have no backend. " +
        "Point MUX_ZMX_BIN_DIR at a bundle from scripts/build-zmx.sh, or use a release build.",
    )
  }
  if (manifest.schema !== 1) return fail(`zmx helper manifest schema ${manifest.schema}, expected 1`)
  if (manifest.abi !== HELPER_ABI) {
    return fail(`zmx helper manifest declares ABI ${manifest.abi}, this build speaks ${HELPER_ABI}`)
  }
  for (const [name, path, want] of [
    ["helper", binaries.helper, manifest.helper?.sha256],
    ["zmx", binaries.zmx, manifest.zmx?.sha256],
  ] as const) {
    if (!existsSync(path)) return fail(`zmx ${name} binary missing at ${path}`)
    if (!want) return fail(`zmx helper manifest has no sha256 for ${name}`)
    const got = fileDigest(path)
    if (got !== want) return fail(`zmx ${name} binary at ${path} is ${got}, manifest says ${want}`)
  }
  return manifest
}

function errorText(error: unknown): string {
  return error instanceof Error ? error.message : String(error)
}

export type HelperHandlers = {
  /**
   * Pty bytes, in order.
   *
   * RETURNING A PROMISE HERE IS NOT BACKPRESSURE, whatever it looks like.
   * Bun drains a subprocess pipe eagerly: a handler that never returns does
   * not slow this process down at all, it only moves the backlog from the
   * helper's socket into Bun's stream buffer, where nothing counts it.
   * Measured: a viewer whose callback was suspended for a 12 MiB flood
   * received every byte late, the daemon's 1 MiB cap never fired, and the
   * helper's RSS stayed flat at 2.5 MB (vendor/zmx/VERIFICATION.md §5).
   *
   * So a handler that can fall behind has to BOUND ITSELF and drop the viewer
   * when it cannot keep up — which is what `ZmxViewer` does.
   */
  onOutput: (bytes: Uint8Array) => void | Promise<void>
  /** Everything else the helper says, already typed and version-checked. */
  onEvent: (event: HelperEvent) => void | Promise<void>
  /**
   * This viewer is over. Called at most once, and NEVER for a target exit —
   * that arrives as an `exit` event through `onEvent`.
   */
  onFailure: (error: WorkspaceTerminalError) => void
}

type Pending = {
  resolve: (result: unknown) => void
  reject: (error: WorkspaceTerminalError) => void
}

/**
 * A running helper. Construct with `startHelper`; one instance is one viewer.
 */
export class ZmxHelper {
  #proc: Bun.Subprocess<"pipe", "pipe", "pipe">
  #handlers: HelperHandlers
  #decoder = new FrameDecoder()
  #pending = new Map<number, Pending>()
  #nextId = 1
  #stderr = ""
  #closing = false
  #done = false
  readonly manifest: HelperManifest
  /** The helper's own account of itself, from its first frame. */
  hello: Extract<HelperEvent, { ev: "hello" }> | null = null

  readonly #sendTimeoutMs: number

  private constructor(
    proc: Bun.Subprocess<"pipe", "pipe", "pipe">,
    manifest: HelperManifest,
    handlers: HelperHandlers,
    sendTimeoutMs: number = SEND_TIMEOUT_MS,
  ) {
    this.#sendTimeoutMs = sendTimeoutMs
    this.#proc = proc
    this.#manifestCheck(manifest)
    this.manifest = manifest
    this.#handlers = handlers
    void this.#readStdout()
    void this.#readStderr()
    void this.#watchExit()
  }

  #manifestCheck(manifest: HelperManifest): void {
    if (manifest.abi !== HELPER_ABI) {
      throw new WorkspaceTerminalError("backend-unavailable", `helper ABI ${manifest.abi} != ${HELPER_ABI}`)
    }
  }

  static async launch(handlers: HelperHandlers, options: {
    binaries?: HelperBinaries
    /** Extra environment for the helper process itself (not the target). */
    env?: Record<string, string>
    signal?: AbortSignal
    /** Per-command ack budget. Exposed for tests; see SEND_TIMEOUT_MS. */
    sendTimeoutMs?: number
  } = {}): Promise<ZmxHelper> {
    // Checked BEFORE the spawn, not only subscribed to after it: an already
    // aborted signal would otherwise leave a helper process running with
    // nobody holding a handle to it.
    if (options.signal?.aborted) {
      throw new WorkspaceTerminalError("backend-unavailable", "zmx helper launch was aborted", true)
    }
    const binaries = options.binaries ?? helperBinaries()
    const manifest = verifyHelperManifest(binaries)

    let proc: Bun.Subprocess<"pipe", "pipe", "pipe">
    try {
      proc = Bun.spawn({
        // Structured argv: no shell, no string interpolation, nothing a path
        // with a space in it can re-parse.
        cmd: [binaries.helper, "--zmx", binaries.zmx],
        stdin: "pipe",
        stdout: "pipe",
        stderr: "pipe",
        env: { ...options.env },
      }) as Bun.Subprocess<"pipe", "pipe", "pipe">
    } catch (error) {
      throw new WorkspaceTerminalError(
        "backend-unavailable",
        `cannot start zmx helper ${binaries.helper}: ${errorText(error)}`,
        true,
      )
    }

    const helper = new ZmxHelper(proc, manifest, handlers, options.sendTimeoutMs ?? SEND_TIMEOUT_MS)
    // The signal may have fired between the check above and here; `aborted`
    // makes the listener run immediately, so the race has one outcome.
    options.signal?.addEventListener("abort", () => helper.kill(), { once: true })
    if (options.signal?.aborted) helper.kill()
    return helper
  }

  get exited(): boolean {
    return this.#done
  }

  /** The helper process's pid. Diagnostics and tests: a viewer that dies for a
   * reason of its own (a crash, an OOM kill) must be distinguishable from one
   * we stopped, and `kill()` cannot tell you that because it IS us stopping it. */
  get pid(): number {
    return this.#proc.pid
  }

  /** The tail of the helper's stderr. Diagnostics only; never parsed. */
  get stderr(): string {
    return this.#stderr
  }

  /**
   * Send a command and wait for its ack.
   *
   * The `v`/`id` envelope is ours to add: a caller cannot accidentally send a
   * command that claims another protocol version.
   */
  async send<T = unknown>(command: HelperCommandBody): Promise<T> {
    if (this.#done) {
      throw new WorkspaceTerminalError("backend-unavailable", "zmx helper is gone", true)
    }
    const id = this.#nextId++
    const frame = encodeControl({ ...command, v: HELPER_PROTOCOL_VERSION, id })
    return new Promise<T>((resolve, reject) => {
      // A helper that is alive but silent answers neither the exit watcher nor
      // the stdout reader, so the ack is bounded here or not at all. The
      // process is killed rather than left holding a command we gave up on.
      const timer = setTimeout(() => {
        if (!this.#pending.delete(id)) return
        reject(new WorkspaceTerminalError(
          "backend-unavailable",
          `zmx helper did not answer ${command.op} within ${this.#sendTimeoutMs}ms`,
          true,
        ))
        this.kill()
      }, this.#sendTimeoutMs)
      // Never keep the process alive for an ack nobody is waiting on any more.
      ;(timer as unknown as { unref?: () => void }).unref?.()
      const settle = <R>(fn: (value: R) => void) => (value: R) => { clearTimeout(timer); fn(value) }
      this.#pending.set(id, {
        resolve: settle(resolve as (value: unknown) => void),
        reject: settle(reject),
      })
      try {
        this.#proc.stdin.write(frame)
        this.#proc.stdin.flush()
      } catch (error) {
        clearTimeout(timer)
        this.#pending.delete(id)
        reject(new WorkspaceTerminalError("backend-unavailable", `zmx helper write failed: ${errorText(error)}`, true))
      }
    })
  }

  /** User keystrokes. Chunked to the frame maximum; order is preserved. */
  write(bytes: Uint8Array): boolean {
    return this.#writeData(bytes, encodeInput)
  }

  /**
   * A terminal reply the viewer's emulator produced.
   *
   * Accepting it here is not a promise that it reaches the pty: replies are
   * OWNER-ONLY, and the helper drops a non-owner's reply exactly as the daemon
   * would. See `reply()` in workspace-backend.ts.
   */
  reply(bytes: Uint8Array): boolean {
    return this.#writeData(bytes, encodeReply)
  }

  #writeData(bytes: Uint8Array, encode: (chunk: Uint8Array) => Uint8Array): boolean {
    if (this.#done || this.#closing) return false
    try {
      for (const chunk of outputChunks(bytes)) this.#proc.stdin.write(encode(chunk))
      if (bytes.length === 0) this.#proc.stdin.write(encode(bytes))
      this.#proc.stdin.flush()
      return true
    } catch {
      return false
    }
  }

  /**
   * Detach this viewer and stop the process. The TARGET SURVIVES.
   *
   * Bounded: a helper that will not answer is killed, because a broker
   * shutting down cannot wait on a pipe.
   */
  async close(): Promise<void> {
    if (this.#done || this.#closing) return
    this.#closing = true
    // The fallback timer is unref'd and cleared: a detach that answers
    // immediately must not leave a 2-second timer holding the event loop open
    // (a broker shutting down closes every viewer at once), and a timer nobody
    // is waiting on any more must not keep the process alive by itself.
    let timer: ReturnType<typeof setTimeout> | undefined
    try {
      await Promise.race([
        this.send({ op: "detach" }),
        new Promise(resolve => {
          timer = setTimeout(resolve, CLOSE_TIMEOUT_MS)
          ;(timer as unknown as { unref?: () => void }).unref?.()
        }),
      ])
    } catch {
      // A helper that cannot answer a detach is one we kill; the target is
      // unaffected either way.
    } finally {
      if (timer !== undefined) clearTimeout(timer)
    }
    this.kill()
  }

  /** Stop the process now. The target survives; this is a viewer detach. */
  kill(): void {
    this.#closing = true
    try {
      this.#proc.kill()
    } catch {
      // Already gone.
    }
  }

  async #readStdout(): Promise<void> {
    try {
      for await (const chunk of this.#proc.stdout as ReadableStream<Uint8Array>) {
        let frames
        try {
          frames = this.#decoder.push(chunk)
        } catch (error) {
          // Deliver what was valid before the bad byte, THEN fail: those bytes
          // are real output the viewer is owed.
          if (error instanceof HelperProtocolError) await this.#dispatch(error.decoded)
          this.#failed(new WorkspaceTerminalError(
            "protocol",
            `zmx helper stream is malformed: ${errorText(error)}`,
            false,
          ))
          this.kill()
          return
        }
        await this.#dispatch(frames)
      }
      try {
        this.#decoder.end()
      } catch (error) {
        this.#failed(new WorkspaceTerminalError("protocol", errorText(error), false))
      }
    } catch (error) {
      if (!this.#done) {
        this.#failed(new WorkspaceTerminalError(
          "backend-unavailable",
          `zmx helper stdout failed: ${errorText(error)}`,
          true,
        ))
      }
    }
  }

  async #dispatch(frames: ReturnType<FrameDecoder["push"]>): Promise<void> {
    for (const frame of frames) {
      if (frame.type === "output") {
        await this.#handlers.onOutput(frame.bytes)
        continue
      }
      if (frame.type !== "control") {
        this.#failed(new WorkspaceTerminalError("protocol", `helper sent a ${frame.type} frame`, false))
        this.kill()
        return
      }
      const event = asEvent(frame.message)
      if (!event) {
        this.#failed(new WorkspaceTerminalError("protocol", "helper control frame has no ev", false))
        this.kill()
        return
      }
      await this.#handleEvent(event)
    }
  }

  async #handleEvent(event: HelperEvent): Promise<void> {
    switch (event.ev) {
      case "hello": {
        this.hello = event
        // The manifest says what was BUILT; this says what is RUNNING. Both
        // have to agree with us before a single byte of this stream is acted
        // on as terminal output.
        if (event.abi !== HELPER_ABI) {
          this.#failed(new WorkspaceTerminalError(
            "protocol",
            `zmx helper speaks ABI ${event.abi}, this build speaks ${HELPER_ABI}`,
            false,
          ))
          this.kill()
          return
        }
        break
      }
      case "ok": {
        const pending = this.#pending.get(event.id)
        if (pending) {
          this.#pending.delete(event.id)
          pending.resolve(event.result)
        }
        break
      }
      case "error": {
        const error = new WorkspaceTerminalError(
          helperErrorCode(event.code),
          event.message,
          event.code === "backend-unavailable",
        )
        if (event.id !== null) {
          const pending = this.#pending.get(event.id)
          if (pending) {
            this.#pending.delete(event.id)
            pending.reject(error)
            break
          }
        }
        this.#failed(error)
        break
      }
      case "detached": {
        // The daemon dropped this viewer, and said why. `resync_required` is
        // recoverable BY RE-ATTACHING (the dropped bytes are exactly the ones
        // that must not be drawn over the next epoch); the others are not
        // this viewer's to retry.
        const reason = event.reason || DETACH_REASONS[event.code] || "unknown"
        this.#failed(new WorkspaceTerminalError(
          "backend-unavailable",
          `zmx detached this viewer: ${reason}${event.message ? ` (${event.message})` : ""}`,
          reason === "resync_required",
        ))
        break
      }
      case "lost": {
        // NOT an exit. We lost sight of the target; whether it is still
        // running is unknown, and a re-attach is how we find out.
        this.#failed(new WorkspaceTerminalError("backend-unavailable", event.message, true))
        break
      }
      case "failure": {
        this.#failed(new WorkspaceTerminalError(
          "protocol",
          `zmx refused this viewer: ${event.name}${event.message ? ` (${event.message})` : ""}`,
          false,
        ))
        break
      }
      default:
        break
    }
    await this.#handlers.onEvent(event)
  }

  async #readStderr(): Promise<void> {
    try {
      const decoder = new TextDecoder()
      for await (const chunk of this.#proc.stderr as ReadableStream<Uint8Array>) {
        // Bounded: a helper looping on a diagnostic must not grow the broker.
        this.#stderr = (this.#stderr + decoder.decode(chunk, { stream: true })).slice(-STDERR_LIMIT)
      }
    } catch {
      // stderr is diagnostics; losing it is never a viewer failure.
    }
  }

  async #watchExit(): Promise<void> {
    const code = await this.#proc.exited
    this.#done = true
    const pendingError = new WorkspaceTerminalError(
      "backend-unavailable",
      `zmx helper exited with code ${code}`,
      true,
    )
    for (const [, pending] of this.#pending) pending.reject(pendingError)
    this.#pending.clear()
    if (this.#closing) return
    // An unexpected helper exit is a lost VIEWER. It says nothing about the
    // target, which is a separate process this one never owned.
    this.#failed(new WorkspaceTerminalError(
      "backend-unavailable",
      `zmx helper exited with code ${code}${this.#stderr ? `: ${this.#stderr.trim().split("\n").at(-1)}` : ""}`,
      true,
    ))
  }

  #failedOnce = false
  #failed(error: WorkspaceTerminalError): void {
    if (this.#failedOnce) return
    this.#failedOnce = true
    this.#handlers.onFailure(error)
  }
}

function helperErrorCode(code: string): WorkspaceTerminalError["code"] {
  switch (code) {
    case "target-not-found":
    case "name-too-long":
    case "socket-dir-unsafe":
    case "backend-unavailable":
    case "protocol":
      return code
    default:
      return "protocol"
  }
}
