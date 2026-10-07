export type RuntimeTarget = { id: string; name: string; pid: number | null; alive: boolean }
export type RuntimeViewer = {
  close(): void
  write(data: Uint8Array): boolean
  resize(cols: number, rows: number): boolean
  /** Present when the backend can report the target process exit to a viewer. */
  readonly exited?: Promise<number>
  /** Cancellable alternative for long-lived viewers. */
  onExit?(handler: (code: number) => void): () => void
  onFailure?(handler: (reason: string) => void): () => void
}

export interface SessionBackend {
  create(opts: { group: string; name: string; cwd: string; argv: string[]; env: Record<string, string>; cols?: number; rows?: number }): Promise<RuntimeTarget>
  list(group?: string): Promise<RuntimeTarget[]>
  resolve(group: string, name: string): Promise<string | null>
  livePid(targetId: string): Promise<number | null>
  write(targetId: string, data: Uint8Array): Promise<void>
  sendKeys(targetId: string, keys: string[]): Promise<void>
  resize(targetId: string, cols: number, rows: number): Promise<void>
  capture(targetId: string, raw?: boolean): Promise<string | null>
  /**
   * Attach a viewer.
   *
   * `replay` says whether the chunk is part of the attach REPLAY (the target's
   * history, queued inside the attach barrier) rather than live output. It is
   * the boundary a caller must use: "everything that arrived before `attach()`
   * resolved" is a timing heuristic — the delivery pump is not the attach
   * promise — and a boundary that closes early lets an answer to a query that
   * scrolled past reach the pty as typing. A backend with no replay of its own
   * passes `false`.
   */
  attach(targetId: string, viewerId: string, onData: (data: Uint8Array, replay: boolean) => void | Promise<void>): Promise<RuntimeViewer>
  interrupt(targetId: string): Promise<void>
  kill(targetId: string): Promise<void>
}
