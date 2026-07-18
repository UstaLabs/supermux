import { Terminal } from "@xterm/headless"

export interface ScreenDimensions {
  cols: number
  rows: number
}

export class SessionScreen {
  private readonly terminal: Terminal
  private readonly rawChunks: Uint8Array[] = []
  private rawBytes = 0
  private disposed = false

  constructor(
    cols: number,
    rows: number,
    private readonly rawByteLimit: number,
  ) {
    assertDimension("cols", cols)
    assertDimension("rows", rows)
    if (!Number.isInteger(rawByteLimit) || rawByteLimit < 0) {
      throw new RangeError("rawByteLimit must be a non-negative integer")
    }

    this.terminal = new Terminal({ cols, rows, allowProposedApi: true })
  }

  async write(data: Uint8Array): Promise<void> {
    this.assertActive()

    // Both xterm and the raw ring must see an immutable snapshot. PTY read
    // buffers are commonly reused after their consumer returns.
    const snapshot = data.slice()
    this.appendRaw(snapshot)

    await new Promise<void>((resolve) => {
      this.terminal.write(snapshot, resolve)
    })
  }

  captureText(): string {
    this.assertActive()
    const buffer = this.terminal.buffer.active
    const start = Math.max(0, buffer.baseY - buffer.viewportY)
    const end = Math.min(buffer.length, buffer.baseY + this.terminal.rows)
    const lines: string[] = []

    for (let index = start; index < end; index++) {
      const text = buffer.getLine(index)?.translateToString(true) ?? ""
      lines.push(text.replace(/[ \t]+$/u, ""))
    }

    // Empty rows below the cursor are viewport capacity, not output. Keep all
    // empty rows between content so intentional vertical spacing survives.
    while (lines.at(-1) === "") lines.pop()
    return lines.join("\n")
  }

  captureRaw(): string {
    this.assertActive()
    const bytes = new Uint8Array(this.rawBytes)
    let offset = 0
    for (const chunk of this.rawChunks) {
      bytes.set(chunk, offset)
      offset += chunk.byteLength
    }
    return new TextDecoder().decode(bytes)
  }

  resize(cols: number, rows: number): void {
    this.assertActive()
    assertDimension("cols", cols)
    assertDimension("rows", rows)
    this.terminal.resize(cols, rows)
  }

  dimensions(): ScreenDimensions {
    this.assertActive()
    return { cols: this.terminal.cols, rows: this.terminal.rows }
  }

  dispose(): void {
    if (this.disposed) return
    this.disposed = true
    this.terminal.dispose()
    this.rawChunks.length = 0
    this.rawBytes = 0
  }

  private appendRaw(data: Uint8Array): void {
    if (this.rawByteLimit === 0 || data.byteLength === 0) return

    if (data.byteLength >= this.rawByteLimit) {
      this.rawChunks.length = 0
      const tail = data.slice(data.byteLength - this.rawByteLimit)
      this.rawChunks.push(tail)
      this.rawBytes = tail.byteLength
      return
    }

    this.rawChunks.push(data)
    this.rawBytes += data.byteLength
    let excess = this.rawBytes - this.rawByteLimit

    while (excess > 0) {
      const first = this.rawChunks[0]
      if (!first) break
      if (first.byteLength <= excess) {
        this.rawChunks.shift()
        this.rawBytes -= first.byteLength
        excess -= first.byteLength
        continue
      }

      this.rawChunks[0] = first.slice(excess)
      this.rawBytes -= excess
      excess = 0
    }
  }

  private assertActive(): void {
    if (this.disposed) throw new Error("SessionScreen is disposed")
  }
}

function assertDimension(name: "cols" | "rows", value: number): void {
  if (!Number.isInteger(value) || value <= 0 || value > 1000) {
    throw new RangeError(`${name} must be a positive integer between 1 and 1000`)
  }
}
