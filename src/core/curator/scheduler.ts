import { Cron } from "croner"
import { makeLogger } from "../../shared/log"
import { type CuratorConfig, toCron } from "../settings/curator-config"

const log = makeLogger("curator/scheduler")

/**
 * Schedules the nightly curator on a real cron engine (croner) instead of
 * hand-rolled time math. `reconfigure` is the live-edit entry point: it stops any
 * running job and starts a fresh one from the new config (or none if disabled),
 * so a settings-page save reschedules without a broker restart. Errors in `run`
 * are logged + swallowed; the schedule survives. croner uses the host's local
 * timezone by default, matching the previous local-hour behaviour.
 */
export class CuratorScheduler {
  private job: Cron | null = null

  constructor(private readonly run: () => Promise<void>) {}

  reconfigure(cfg: CuratorConfig): void {
    this.job?.stop()
    this.job = null
    if (!cfg.enabled) {
      log.info("curator_disabled")
      return
    }
    const expr = toCron(cfg)
    this.job = new Cron(expr, async () => {
      try {
        await this.run()
      } catch (err: any) {
        log.warn("curator_run_failed", { err: err?.message ?? String(err) })
      }
    })
    log.info("curator_scheduled", { cron: expr, next: this.job.nextRun()?.toISOString() })
  }

  /** Next scheduled fire time, or null when disabled. For the settings UI. */
  nextRun(): Date | null {
    return this.job?.nextRun() ?? null
  }

  stop(): void {
    this.job?.stop()
    this.job = null
  }
}
