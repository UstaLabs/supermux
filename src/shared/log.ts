type Level = "debug" | "info" | "warn" | "error"
const LEVELS: Record<Level, number> = { debug: 10, info: 20, warn: 30, error: 40 }

function currentMinLevel(): number {
  const v = (process.env.MUX_LOG_LEVEL ?? "info") as Level
  return LEVELS[v] ?? LEVELS.info
}

export type Logger = {
  debug: (msg: string, extra?: Record<string, unknown>) => void
  info:  (msg: string, extra?: Record<string, unknown>) => void
  warn:  (msg: string, extra?: Record<string, unknown>) => void
  error: (msg: string, extra?: Record<string, unknown>) => void
}

export function makeLogger(module: string): Logger {
  function emit(level: Level, msg: string, extra?: Record<string, unknown>) {
    if (LEVELS[level] < currentMinLevel()) return
    const ts = new Date().toISOString()
    const tag = `[${ts}] [${level.toUpperCase()}] [${module}]`
    let line = `${tag} ${msg}`
    if (extra) {
      let payload: string
      try { payload = JSON.stringify(extra) }
      catch { payload = "[unserializable]" }
      line = `${line} ${payload}`
    }
    process.stderr.write(line + "\n")
  }
  return {
    debug: (msg, extra) => emit("debug", msg, extra),
    info:  (msg, extra) => emit("info",  msg, extra),
    warn:  (msg, extra) => emit("warn",  msg, extra),
    error: (msg, extra) => emit("error", msg, extra),
  }
}
