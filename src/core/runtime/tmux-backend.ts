import { randomUUID } from "node:crypto"
import {
  capturePaneById,
  capturePaneRawById,
  killWindowById,
  listSessionWindows,
  livePanePid,
  resolveWindowIdByName,
  runCommand,
  sendKeysToWindowId,
  spawnSessionWindow,
} from "../session-manager/tmux"
import type { SessionBackend } from "./session-backend"

type TmuxResult = { code: number; stdout: string; stderr: string }

type TmuxClient = {
  spawnSessionWindow(opts: { session: string; window: string; workdir: string; command: string; env?: Record<string, string> }): Promise<{ windowId: string }>
  killWindowById(windowId: string): Promise<void>
  listSessionWindows(session: string): Promise<string[]>
  livePanePid(windowId: string): Promise<number | null>
  sendKeysToWindowId(windowId: string, keys: string[]): Promise<void>
  capturePaneById(windowId: string): Promise<string | null>
  capturePaneRawById(windowId: string): Promise<string | null>
  resolveWindowIdByName(session: string, name: string): Promise<string | null>
}

type TmuxCommandRunner = (args: string[], input?: Uint8Array) => Promise<TmuxResult>

const tmuxClient: TmuxClient = {
  spawnSessionWindow,
  killWindowById,
  listSessionWindows,
  livePanePid,
  sendKeysToWindowId,
  capturePaneById,
  capturePaneRawById,
  resolveWindowIdByName,
}

async function runTmux(args: string[], input?: Uint8Array): Promise<TmuxResult> {
  if (input === undefined) return runCommand("tmux", args)

  const proc = Bun.spawn(["tmux", ...args], { stdin: "pipe", stdout: "pipe", stderr: "pipe" })
  proc.stdin.write(input)
  proc.stdin.end()
  const code = await proc.exited
  const [stdout, stderr] = await Promise.all([
    new Response(proc.stdout).text(),
    new Response(proc.stderr).text(),
  ])
  return { code, stdout, stderr }
}

function quotePosix(value: string): string {
  return `'${value.replaceAll("'", `'"'"'`)}'`
}

function renderCommand(argv: string[], env: Record<string, string>): string {
  const environment = Object.entries(env).map(([key, value]) => quotePosix(`${key}=${value}`))
  return ["env", ...environment, ...argv.map(quotePosix)].join(" ")
}

function ensureTmux(result: TmuxResult, operation: string): void {
  if (result.code !== 0) throw new Error(`tmux ${operation} failed: ${result.stderr}`)
}

export function createTmuxSessionBackend(deps: {
  tmux?: TmuxClient
  runTmux?: TmuxCommandRunner
  defaultGroup?: string
  bufferId?: () => string
} = {}): SessionBackend {
  const tmux = deps.tmux ?? tmuxClient
  const command = deps.runTmux ?? runTmux
  const defaultGroup = deps.defaultGroup ?? process.env.MUX_TMUX_SESSION ?? "mux"
  const bufferId = deps.bufferId ?? (() => `mux-runtime-${randomUUID()}`)

  return {
    async create(opts) {
      const { windowId } = await tmux.spawnSessionWindow({
        session: opts.group,
        window: opts.name,
        workdir: opts.cwd,
        command: renderCommand(opts.argv, opts.env),
      })
      if (opts.cols !== undefined || opts.rows !== undefined) {
        const args = ["resize-window", "-t", windowId]
        if (opts.cols !== undefined) args.push("-x", String(opts.cols))
        if (opts.rows !== undefined) args.push("-y", String(opts.rows))
        ensureTmux(await command(args), "resize-window")
      }
      const pid = await tmux.livePanePid(windowId)
      return { id: windowId, name: opts.name, pid, alive: pid !== null }
    },
    async list(group = defaultGroup) {
      const names = await tmux.listSessionWindows(group)
      const targets = await Promise.all(names.map(async name => {
        const id = await tmux.resolveWindowIdByName(group, name)
        if (id === null) return null
        const pid = await tmux.livePanePid(id)
        return { id, name, pid, alive: pid !== null }
      }))
      return targets.filter(target => target !== null)
    },
    resolve(group, name) {
      return tmux.resolveWindowIdByName(group, name)
    },
    livePid(targetId) {
      return tmux.livePanePid(targetId)
    },
    async write(targetId, data) {
      const name = bufferId()
      ensureTmux(await command(["load-buffer", "-b", name, "-"], data), "load-buffer")
      ensureTmux(await command(["paste-buffer", "-d", "-b", name, "-t", targetId]), "paste-buffer")
    },
    sendKeys(targetId, keys) {
      return tmux.sendKeysToWindowId(targetId, keys)
    },
    async resize(targetId, cols, rows) {
      ensureTmux(await command(["resize-window", "-t", targetId, "-x", String(cols), "-y", String(rows)]), "resize-window")
    },
    capture(targetId, raw = false) {
      return raw ? tmux.capturePaneRawById(targetId) : tmux.capturePaneById(targetId)
    },
    async attach() {
      throw new Error("tmux backend viewer attachment is owned by TerminalManager")
    },
    interrupt(targetId) {
      return tmux.sendKeysToWindowId(targetId, ["C-c"])
    },
    kill(targetId) {
      return tmux.killWindowById(targetId)
    },
  }
}
