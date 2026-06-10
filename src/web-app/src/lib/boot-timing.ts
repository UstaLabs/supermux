import { clientDebug, flushClientLogs } from "./client-debug"

// Field diagnostics for the blank-screen-on-load reports (Android Chrome shows
// white for minutes before first paint). We can't attach DevTools to the
// affected phones, so decompose navigation → first paint into network / SW /
// JS phases via the Performance API and ship the breakdown through the
// existing client_logs channel. Read back with:
//   journalctl --user -u mux | grep 'client_log' | grep '"category":"boot"'
// or GET /debug/client-logs?category=boot

const r = (n: number) => Math.round(n)

function navSummary(): Record<string, unknown> | undefined {
  const nav = performance.getEntriesByType("navigation")[0] as PerformanceNavigationTiming | undefined
  if (!nav) return undefined
  return {
    type: nav.type,
    redirects: nav.redirectCount,
    workerStart: r(nav.workerStart),
    fetchStart: r(nav.fetchStart),
    dnsStart: r(nav.domainLookupStart),
    dnsEnd: r(nav.domainLookupEnd),
    connectStart: r(nav.connectStart),
    tlsStart: r(nav.secureConnectionStart),
    connectEnd: r(nav.connectEnd),
    requestStart: r(nav.requestStart),
    ttfb: r(nav.responseStart),
    responseEnd: r(nav.responseEnd),
    domContentLoaded: r(nav.domContentLoadedEventEnd),
    loadEnd: r(nav.loadEventEnd),
    transferSize: nav.transferSize,
    bodySize: nav.decodedBodySize,
    protocol: nav.nextHopProtocol,
  }
}

function entryAssets(): Array<Record<string, unknown>> {
  const res = performance.getEntriesByType("resource") as PerformanceResourceTiming[]
  return res
    .filter((e) => /\/assets\/index-|\/sw\.js|\/registerSW\.js/.test(e.name))
    .slice(0, 6)
    .map((e) => ({
      name: e.name.split("/").pop(),
      start: r(e.startTime),
      workerStart: r(e.workerStart),
      ttfb: r(e.responseStart),
      end: r(e.responseEnd),
      transferSize: e.transferSize,
      bodySize: e.decodedBodySize,
    }))
}

function connectionInfo(): Record<string, unknown> | undefined {
  const c = (navigator as unknown as { connection?: { effectiveType?: string; rtt?: number; downlink?: number; saveData?: boolean } }).connection
  if (!c) return undefined
  return { effectiveType: c.effectiveType, rtt: c.rtt, downlink: c.downlink, saveData: c.saveData }
}

export function recordBootMark(event: string, data?: Record<string, unknown>): void {
  clientDebug("boot", event, { perfNow: r(performance.now()), visibility: document.visibilityState, ...data })
}

export function initBootTiming(): void {
  let fcp: number | null = null
  let lcp: number | null = null
  let loaded = document.readyState === "complete"
  let reported = false

  recordBootMark("main_eval", {
    readyState: document.readyState,
    controlled: Boolean(navigator.serviceWorker?.controller),
    prerendering: (document as unknown as { prerendering?: boolean }).prerendering === true,
    wasDiscarded: (document as unknown as { wasDiscarded?: boolean }).wasDiscarded === true,
  })

  document.addEventListener("visibilitychange", () => {
    clientDebug("boot", "visibility", { perfNow: r(performance.now()), visibility: document.visibilityState })
  })

  const report = (reason: string) => {
    if (reported) return
    reported = true
    clientDebug("boot", "timing", {
      reason,
      fcp: fcp === null ? null : r(fcp),
      lcp: lcp === null ? null : r(lcp),
      nav: navSummary(),
      assets: entryAssets(),
      conn: connectionInfo(),
      visibility: document.visibilityState,
      controlled: Boolean(navigator.serviceWorker?.controller),
    })
    void flushClientLogs({ reason: "boot_timing" })
  }
  const maybeReport = () => {
    if (fcp !== null && loaded) report("fcp+load")
  }

  try {
    new PerformanceObserver((list) => {
      for (const e of list.getEntries()) {
        if (e.name === "first-contentful-paint" && fcp === null) {
          fcp = e.startTime
          // A paint that lands after we already reported IS the blank-screen
          // signature — ship it as its own entry.
          if (reported) {
            clientDebug("boot", "late_fcp", { at: r(e.startTime) })
            void flushClientLogs({ reason: "late_fcp" })
          }
        }
      }
      maybeReport()
    }).observe({ type: "paint", buffered: true })
  } catch { /* paint timing unsupported */ }

  try {
    new PerformanceObserver((list) => {
      for (const e of list.getEntries()) lcp = e.startTime
    }).observe({ type: "largest-contentful-paint", buffered: true })
  } catch { /* lcp unsupported */ }

  if (loaded) maybeReport()
  else window.addEventListener("load", () => {
    loaded = true
    recordBootMark("window_load")
    // Paint can be ready before load; give the observer a beat then re-check.
    setTimeout(maybeReport, 0)
  })

  // Fallback: if FCP never shows (or stalls for a long time), report what we
  // have — a timing entry with fcp:null + a later late_fcp entry is exactly
  // the evidence the blank-screen investigation needs.
  setTimeout(() => report("timeout_12s"), 12_000)
}
