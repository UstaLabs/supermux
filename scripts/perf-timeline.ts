#!/usr/bin/env bun
// Card #54: read the load-time trace back.
//
//   bun scripts/perf-timeline.ts on          start tracing (the broker sees it within 2 s; then open the app)
//   bun scripts/perf-timeline.ts off         stop tracing
//   bun scripts/perf-timeline.ts status      is it on, how much is recorded
//   bun scripts/perf-timeline.ts list        recent launches and chat opens
//   bun scripts/perf-timeline.ts launch [TRACE] [--device NAME]   one app launch (default: the newest)
//   bun scripts/perf-timeline.ts chat [TRACE] [--device NAME] [--last N]   chat opens (default: the newest)
//   bun scripts/perf-timeline.ts start [TRACE] [--last N]         session starts, send → first reply (card #75)
//   bun scripts/perf-timeline.ts starts [--last N]                 the last N starts (default 10): each step's median, min, max
//   bun scripts/perf-timeline.ts ws [--minutes N]                 WebSocket frames per type (default 10 min)
//
// Reads <state>/perf/perf.jsonl (+ .1), or --file PATH. MUX_STATE_DIR picks another broker's state.
import { existsSync, readFileSync, statSync } from "fs"
import { join } from "path"
import { STATE_DIR } from "../src/shared/paths"
import { PerfLog } from "../src/core/perf/perf-log"
import { latestTrace, listTraces, parsePerfLines, renderChat, renderLaunch, renderStart, renderStartSummary, renderWs } from "../src/core/perf/timeline"

const args = process.argv.slice(2)
const flag = (name: string): string | undefined => {
  const i = args.indexOf(name)
  if (i < 0) return undefined
  const v = args[i + 1]
  args.splice(i, 2)
  return v
}
const file = flag("--file")
const device = flag("--device")
const flagLast = flag("--last")
const last = Number(flagLast ?? "1")
const minutes = Number(flag("--minutes") ?? "10")
const [command = "launch", traceArg] = args

const dir = join(STATE_DIR, "perf")
const perf = new PerfLog({ dir, envEnabled: false, statCacheMs: 0 })

function load(): string {
  const files = file ? [file] : [join(dir, "perf.jsonl.1"), join(dir, "perf.jsonl")]
  return files.filter(existsSync).map((f) => readFileSync(f, "utf8")).join("\n")
}

switch (command) {
  case "on":
    perf.setEnabled(true)
    console.log(`Tracing on (${join(dir, "enabled")}). The broker notices within 2 s.`)
    console.log("Apps start shipping their timings on their next snapshot: relaunch the app to trace a launch,")
    console.log("then open a chat. Read it with: bun scripts/perf-timeline.ts launch | chat | ws")
    break
  case "off":
    perf.setEnabled(false)
    console.log("Tracing off. Already recorded events stay in perf.jsonl.")
    break
  case "status": {
    const f = join(dir, "perf.jsonl")
    const events = parsePerfLines(load())
    console.log(`tracing: ${perf.enabled() ? "ON" : "off"}${process.env.MUX_PERF === "1" ? " (MUX_PERF=1 in this shell)" : ""}`)
    console.log(`file: ${f}${existsSync(f) ? ` (${(statSync(f).size / 1024).toFixed(0)} KB)` : " (none yet)"}, ${events.length} events`)
    const traces = listTraces(events)
    console.log(`launches: ${traces.filter((t) => t.kind === "launch").length}, chat opens: ${traces.filter((t) => t.kind === "chat").length}, session starts: ${traces.filter((t) => t.kind === "start").length}`)
    break
  }
  case "list": {
    for (const t of listTraces(parsePerfLines(load())).slice(-40)) {
      console.log(`${new Date(t.first).toISOString().replace("T", " ").slice(0, 19)}  ${t.kind.padEnd(6)} ${t.trace.padEnd(14)} ${(t.surface ?? "").padEnd(8)} ${t.device ?? ""}  (${t.events} events)`)
    }
    break
  }
  case "launch": {
    const events = parsePerfLines(load())
    const trace = traceArg ?? latestTrace(events, "launch", device)
    console.log(trace ? renderLaunch(events, trace) : "No launch recorded yet. `bun scripts/perf-timeline.ts on`, then open the app.")
    break
  }
  case "chat": {
    const events = parsePerfLines(load())
    const traces = traceArg ? [traceArg] : listTraces(events).filter((t) => t.kind === "chat" && (device === undefined || t.device === device)).slice(-Math.max(1, last)).map((t) => t.trace)
    console.log(traces.length ? traces.map((t) => renderChat(events, t)).join("\n\n") : "No chat open recorded yet.")
    break
  }
  case "start": {
    const events = parsePerfLines(load())
    const traces = traceArg ? [traceArg] : listTraces(events).filter((t) => t.kind === "start" && (device === undefined || t.device === device)).slice(-Math.max(1, last)).map((t) => t.trace)
    console.log(traces.length ? traces.map((t) => renderStart(events, t)).join("\n\n") : "No session start recorded yet. `bun scripts/perf-timeline.ts on`, then start a session.")
    break
  }
  case "starts": {
    const events = parsePerfLines(load())
    const traces = listTraces(events).filter((t) => t.kind === "start" && (device === undefined || t.device === device)).slice(-Math.max(1, Number(flagLast ?? 10))).map((t) => t.trace)
    console.log(renderStartSummary(events, traces))
    break
  }
  case "ws": {
    const events = parsePerfLines(load())
    console.log(renderWs(events, Date.now() - minutes * 60_000))
    break
  }
  default:
    console.log("usage: bun scripts/perf-timeline.ts on|off|status|list|launch [TRACE]|chat [TRACE]|start [TRACE]|starts|ws [--minutes N] [--device NAME] [--last N] [--file PATH]")
    process.exit(1)
}
