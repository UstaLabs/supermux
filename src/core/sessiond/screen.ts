import { Terminal } from "@xterm/headless"

export interface ScreenDimensions {
  cols: number
  rows: number
}

/** @internal Fixed-capacity storage for the newest bytes of a PTY stream. */
export class ByteRing {
  private storage: Uint8Array
  private start = 0
  private length = 0
  private disposed = false

  constructor(private readonly capacity: number) {
    if (!Number.isInteger(capacity) || capacity < 0) {
      throw new RangeError("capacity must be a non-negative integer")
    }
    this.storage = new Uint8Array(capacity)
  }

  append(data: Uint8Array): void {
    this.assertActive()
    if (this.capacity === 0 || data.byteLength === 0) return

    if (data.byteLength >= this.capacity) {
      this.storage.set(data.subarray(data.byteLength - this.capacity))
      this.start = 0
      this.length = this.capacity
      return
    }

    const writeAt = (this.start + this.length) % this.capacity
    const free = this.capacity - this.length
    const evicted = Math.max(0, data.byteLength - free)
    if (evicted > 0) this.start = (this.start + evicted) % this.capacity

    this.writeAt(writeAt, data)
    this.length = Math.min(this.capacity, this.length + data.byteLength)
  }

  bytes(): Uint8Array {
    this.assertActive()
    const result = new Uint8Array(this.length)
    if (this.length === 0) return result

    const firstLength = Math.min(this.length, this.capacity - this.start)
    result.set(this.storage.subarray(this.start, this.start + firstLength))
    if (firstLength < this.length) {
      result.set(this.storage.subarray(0, this.length - firstLength), firstLength)
    }
    return result
  }

  dispose(): void {
    if (this.disposed) return
    this.disposed = true
    this.storage = new Uint8Array(0)
    this.start = 0
    this.length = 0
  }

  private writeAt(offset: number, data: Uint8Array): void {
    const firstLength = Math.min(data.byteLength, this.capacity - offset)
    this.storage.set(data.subarray(0, firstLength), offset)
    if (firstLength < data.byteLength) {
      this.storage.set(data.subarray(firstLength), 0)
    }
  }

  private assertActive(): void {
    if (this.disposed) throw new Error("ByteRing is disposed")
  }
}

export class SessionScreen {
  private readonly terminal: Terminal
  private readonly raw: ByteRing
  private disposed = false

  constructor(
    cols: number,
    rows: number,
    rawByteLimit: number,
  ) {
    assertDimension("cols", cols)
    assertDimension("rows", rows)
    if (!Number.isInteger(rawByteLimit) || rawByteLimit < 0) {
      throw new RangeError("rawByteLimit must be a non-negative integer")
    }

    this.raw = new ByteRing(rawByteLimit)
    this.terminal = new Terminal({ cols, rows, allowProposedApi: true })
  }

  async write(data: Uint8Array): Promise<void> {
    this.assertActive()

    // Both xterm and the raw ring must see an immutable snapshot. PTY read
    // buffers are commonly reused after their consumer returns.
    const snapshot = data.slice()
    this.raw.append(snapshot)

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
    return new TextDecoder().decode(this.captureRawBytes())
  }

  captureRawBytes(): Uint8Array {
    this.assertActive()
    return this.raw.bytes()
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
    this.raw.dispose()
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
