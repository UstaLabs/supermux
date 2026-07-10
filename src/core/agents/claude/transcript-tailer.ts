// src/core/agents/claude/transcript-tailer.ts
import { watchFile, unwatchFile, openSync, readSync, closeSync, statSync } from "fs"
import { StringDecoder } from "string_decoder"
import { parseTranscriptLine } from "./transcript-parser"
import type { ActivityEvent } from "./activity-event"

export interface TranscriptTailerOpts {
  path: string
  onEvent: (event: ActivityEvent) => void
  onLine?: (line: string) => void   // raw-line tap for stateful detectors (bg tasks)
  intervalMs?: number
  seekToEnd?: boolean
}

export class TranscriptTailer {
  private readonly path: string
  private readonly onEvent: (e: ActivityEvent) => void
  private readonly onLine?: (line: string) => void
  private readonly intervalMs: number
  private readonly seekToEnd: boolean
  private offset = 0
  private buffer = ""
  private watching = false
  private decoder = new StringDecoder("utf8")

  constructor(opts: TranscriptTailerOpts) {
    this.path = opts.path
    this.onEvent = opts.onEvent
    this.onLine = opts.onLine
    this.intervalMs = opts.intervalMs ?? 300
    this.seekToEnd = opts.seekToEnd ?? false
  }

  // Pure: feed a chunk of appended text; emit an event per complete line.
  // Exposed for testing and used by the fs poller.
  ingest(chunk: string): void {
    this.buffer += chunk
    let nl: number
    while ((nl = this.buffer.indexOf("\n")) !== -1) {
      const line = this.buffer.slice(0, nl)
      this.buffer = this.buffer.slice(nl + 1)
      this.onLine?.(line)
      for (const ev of parseTranscriptLine(line)) this.onEvent(ev)
    }
  }

  start(): void {
    if (this.watching) return
    this.watching = true
    if (this.seekToEnd) {
      try { this.offset = statSync(this.path).size } catch { /* file not created yet */ }
    }
    watchFile(this.path, { interval: this.intervalMs }, () => this.readDelta())
    this.readDelta() // catch up if the file already exists
  }

  stop(): void {
    if (!this.watching) return
    this.watching = false
    unwatchFile(this.path)
  }

  private readDelta(): void {
    if (!this.watching) return
    let fd: number | undefined
    try {
      const size = statSync(this.path).size
      if (size < this.offset) { this.offset = 0; this.buffer = ""; this.decoder = new StringDecoder("utf8") } // rotated/truncated
      if (size === this.offset) return
      const len = size - this.offset
      const buf = Buffer.allocUnsafe(len)
      fd = openSync(this.path, "r")
      const read = readSync(fd, buf, 0, len, this.offset)
      this.offset += read
      this.ingest(this.decoder.write(buf.subarray(0, read)))
    } catch {
      // file may not exist yet, or a transient read error — ignore, poller retries
    } finally {
      if (fd !== undefined) try { closeSync(fd) } catch {}
    }
  }
}
