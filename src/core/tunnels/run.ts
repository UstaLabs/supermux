// Real process runner + small helpers. The `Run` type lives in types.ts; this is
// the production implementation. Tests inject a fake Run instead of using realRun.

import type { Run, RunResult } from "./types"

/**
 * Drain a child stream to a string, optionally teeing each chunk to a live sink
 * (process.stdout/stderr) as it arrives. Teeing is what makes an interactive
 * command's output visible immediately instead of buffered until the process
 * exits — the difference between "I see an auth URL" and a silent hang.
 */
async function drain(
  stream: ReadableStream<Uint8Array> | null,
  sink: { write: (chunk: Uint8Array) => unknown } | null,
): Promise<string> {
  if (!stream) return ""
  const reader = stream.getReader()
  const decoder = new TextDecoder()
  let acc = ""
  try {
    for (;;) {
      const { done, value } = await reader.read()
      if (done) break
      if (value && value.length) {
        if (sink) sink.write(value)
        acc += decoder.decode(value, { stream: true })
      }
    }
  } finally {
    acc += decoder.decode()
    reader.releaseLock()
  }
  return acc
}

/** Production Run: spawn a process, capture stdout/stderr, honor a timeout. */
export const realRun: Run = async (argv, opts) => {
  const stream = opts?.stream === true
  const proc = Bun.spawn(argv, {
    // Interactive (streamed) calls inherit stdin so the user can answer prompts;
    // a piped input still wins. Otherwise stdin is closed.
    stdin: opts?.input !== undefined ? "pipe" : stream ? "inherit" : "ignore",
    stdout: "pipe",
    stderr: "pipe",
  })
  if (opts?.input !== undefined && proc.stdin) {
    proc.stdin.write(opts.input)
    await proc.stdin.end()
  }

  let timedOut = false
  let timer: ReturnType<typeof setTimeout> | undefined
  if (opts?.timeoutMs && opts.timeoutMs > 0) {
    timer = setTimeout(() => {
      timedOut = true
      try {
        proc.kill()
      } catch {
        // already gone
      }
    }, opts.timeoutMs)
  }

  const [stdout, stderr] = await Promise.all([
    drain(proc.stdout, stream ? process.stdout : null),
    drain(proc.stderr, stream ? process.stderr : null),
  ])
  const code = await proc.exited
  if (timer) clearTimeout(timer)

  return {
    code: timedOut ? 124 : code,
    stdout,
    stderr: timedOut ? `${stderr}\n[timed out after ${opts?.timeoutMs}ms]` : stderr,
  } satisfies RunResult
}

/** Is a binary on PATH? */
export function which(bin: string): boolean {
  return Bun.which(bin) !== null
}

/**
 * Pull the first http(s) URL out of arbitrary CLI output. When `host` is given,
 * only a URL whose href matches that regex is returned (e.g. /trycloudflare\.com/).
 */
export function extractFirstUrl(text: string, host?: RegExp): string | undefined {
  const re = /https?:\/\/[^\s"'<>)\]]+/g
  const matches = text.match(re)
  if (!matches) return undefined
  for (const m of matches) {
    const url = m.replace(/[.,;]+$/, "") // trim trailing punctuation
    if (!host || host.test(url)) return url
  }
  return undefined
}
