import type { Database } from "bun:sqlite"
import { proc } from "@supermux/platform"
import { makeLogger } from "../../shared/log"
import { parseEvery, type AppletJob } from "./manifest"

const log = makeLogger("applets")

/**
 * #117: runs applet.json's `jobs` on their schedule, with no session alive, and on demand ("Run now",
 * `supermux applet run`). A job runs in the applet's folder with the same environment as its server (secrets, $DATA_DIR,
 * and $PORT when the server is up, so a job can call it). Each run is a row with its exit code and the tail of its
 * output, so an agent can see what a job really did. One run of a job at a time.
 */

export interface JobRun {
  id: number
  appletId: string
  job: string
  trigger: "schedule" | "manual"
  startedAt: string
  finishedAt: string | null
  exitCode: number | null
  output: string
}

type RunRaw = { id: number; applet_id: string; job: string; trigger: string; started_at: string; finished_at: string | null; exit_code: number | null; output: string }
const fromRaw = (r: RunRaw): JobRun => ({
  id: r.id, appletId: r.applet_id, job: r.job, trigger: r.trigger as JobRun["trigger"], startedAt: r.started_at,
  finishedAt: r.finished_at, exitCode: r.exit_code, output: r.output,
})

/** What a scheduled applet looks like to the scheduler. */
export interface JobTarget {
  appletId: string
  name: string
  folder: string
  jobs: AppletJob[]
  env: () => Record<string, string>
}

export interface JobRunnerDeps {
  db: Database
  /** Starts a job's command line; the platform's shell by default. */
  spawn?: (line: string, opts: { cwd: string; env: Record<string, string> }) => {
    pid?: number
    stdout: NodeJS.ReadableStream | null
    stderr: NodeJS.ReadableStream | null
    onExit: (cb: (code: number | null, signal: string | null) => void) => void
  }
  killTree?: (pid: number) => void
  now?: () => number
  /** Output kept per run. */
  maxOutput?: number
  /** Runs kept per job. */
  keepRuns?: number
}

const MAX_OUTPUT = 64 * 1024

/** A job may run for as long as its interval, at most an hour, so a hung job never piles up. */
export function jobTimeoutMs(every: string): number {
  return Math.min(parseEvery(every) ?? 3_600_000, 3_600_000)
}

function defaultSpawn(line: string, opts: { cwd: string; env: Record<string, string> }) {
  const child = proc.spawnShellLine(line, { cwd: opts.cwd, env: opts.env, detached: true, stdio: ["ignore", "pipe", "pipe"] })
  return {
    pid: child.pid,
    stdout: child.stdout,
    stderr: child.stderr,
    onExit: (cb: (code: number | null, signal: string | null) => void) => {
      let done = false
      child.on("close", (code, signal) => { if (!done) { done = true; cb(code, signal) } })
      child.on("error", (err) => { if (!done) { done = true; cb(127, err.message) } })
    },
  }
}

export class JobRunner {
  private running = new Map<string, Promise<JobRun>>()
  private readonly spawn: NonNullable<JobRunnerDeps["spawn"]>
  private readonly killTree: (pid: number) => void
  private readonly now: () => number

  constructor(private readonly deps: JobRunnerDeps) {
    this.spawn = deps.spawn ?? defaultSpawn
    this.killTree = deps.killTree ?? ((pid) => proc.killTree(pid))
    this.now = deps.now ?? Date.now
    // A run the last broker left unfinished never reports back: say so instead of showing it as running forever.
    deps.db.query("UPDATE applet_job_runs SET finished_at = ?, output = output || ? WHERE finished_at IS NULL")
      .run(new Date(this.now()).toISOString(), "\n[supermux: the broker restarted while this run was going; its end is unknown]")
  }

  isRunning(appletId: string, job: string): boolean {
    return this.running.has(`${appletId}/${job}`)
  }

  lastRun(appletId: string, job: string): JobRun | undefined {
    const r = this.deps.db.query("SELECT * FROM applet_job_runs WHERE applet_id = ? AND job = ? ORDER BY id DESC LIMIT 1").get(appletId, job) as RunRaw | null
    return r ? fromRaw(r) : undefined
  }

  runs(appletId: string, job?: string, limit = 20): JobRun[] {
    const rows = job
      ? this.deps.db.query("SELECT * FROM applet_job_runs WHERE applet_id = ? AND job = ? ORDER BY id DESC LIMIT ?").all(appletId, job, limit)
      : this.deps.db.query("SELECT * FROM applet_job_runs WHERE applet_id = ? ORDER BY id DESC LIMIT ?").all(appletId, limit)
    return (rows as RunRaw[]).map(fromRaw)
  }

  /** Whether [job] is due: never run, or its interval has passed since the last run started. */
  isDue(appletId: string, job: AppletJob): boolean {
    if (this.isRunning(appletId, job.name)) return false
    const every = parseEvery(job.every)
    if (every === null) return false
    const last = this.lastRun(appletId, job.name)
    return !last || this.now() - Date.parse(last.startedAt) >= every
  }

  /** Runs every due job of [targets] (the scheduler's tick). Doesn't wait for them. */
  tick(targets: JobTarget[]): void {
    for (const t of targets) for (const job of t.jobs) {
      if (this.isDue(t.appletId, job)) void this.run(t, job, "schedule")
    }
  }

  /** Runs [job] now, or joins the run already going. Resolves with the finished run. */
  run(t: JobTarget, job: AppletJob, trigger: JobRun["trigger"]): Promise<JobRun> {
    const key = `${t.appletId}/${job.name}`
    const going = this.running.get(key)
    if (going) return going
    const p = this.execute(t, job, trigger).finally(() => this.running.delete(key))
    this.running.set(key, p)
    return p
  }

  private execute(t: JobTarget, job: AppletJob, trigger: JobRun["trigger"]): Promise<JobRun> {
    const db = this.deps.db
    const startedAt = new Date(this.now()).toISOString()
    const id = Number(db.query("INSERT INTO applet_job_runs (applet_id, job, trigger, started_at) VALUES (?, ?, ?, ?)")
      .run(t.appletId, job.name, trigger, startedAt).lastInsertRowid)
    const max = this.deps.maxOutput ?? MAX_OUTPUT
    let out = ""
    let cut = false
    const take = (chunk: Buffer | string) => {
      out += chunk.toString()
      if (out.length > max * 2) { out = out.slice(-max); cut = true }
    }
    return new Promise<JobRun>((resolve) => {
      const finish = (code: number | null, note?: string) => {
        if (timer) clearTimeout(timer)
        let output = out.length > max ? out.slice(-max) : out
        if (cut || out.length > max) output = `[supermux: earlier output cut, last ${Math.round(max / 1024)} KB kept]\n` + output
        if (note) output += (output && !output.endsWith("\n") ? "\n" : "") + `[supermux: ${note}]`
        db.query("UPDATE applet_job_runs SET finished_at = ?, exit_code = ?, output = ? WHERE id = ?")
          .run(new Date(this.now()).toISOString(), code, output, id)
        this.prune(t.appletId, job.name)
        const run = fromRaw(db.query("SELECT * FROM applet_job_runs WHERE id = ?").get(id) as RunRaw)
        if (code !== 0) log.warn("applet.job_failed", { name: t.name, job: job.name, code })
        resolve(run)
      }
      let timer: ReturnType<typeof setTimeout> | undefined
      let child: ReturnType<JobRunner["spawn"]>
      try {
        child = this.spawn(job.run, { cwd: t.folder, env: t.env() })
      } catch (err) {
        finish(127, `could not start: ${(err as Error).message}`)
        return
      }
      child.stdout?.on("data", take)
      child.stderr?.on("data", take)
      let timedOut = false
      timer = setTimeout(() => {
        timedOut = true
        if (child.pid) this.killTree(child.pid)
      }, jobTimeoutMs(job.every))
      child.onExit((code, signal) => {
        if (timedOut) finish(code, `stopped after ${Math.round(jobTimeoutMs(job.every) / 60_000)} min (a job may run as long as its interval, at most an hour)`)
        else if (code === null && signal) finish(null, `ended by signal ${signal}`)
        else if (code === 127 && signal) finish(127, `could not start: ${signal}`)
        else finish(code)
      })
    })
  }

  private prune(appletId: string, job: string): void {
    const keep = this.deps.keepRuns ?? 50
    this.deps.db.query(
      "DELETE FROM applet_job_runs WHERE applet_id = ? AND job = ? AND id NOT IN (SELECT id FROM applet_job_runs WHERE applet_id = ? AND job = ? ORDER BY id DESC LIMIT ?)",
    ).run(appletId, job, appletId, job, keep)
  }

  forget(appletId: string): void {
    this.deps.db.query("DELETE FROM applet_job_runs WHERE applet_id = ?").run(appletId)
  }
}
