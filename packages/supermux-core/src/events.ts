import { asError } from "./errors.js"
import type { CoreEvent, Observer } from "./types.js"

export class Events {
  private observers = new Set<Observer>()
  constructor(private onError?: (error: Error) => void) {}

  subscribe(observer: Observer): () => void {
    this.observers.add(observer)
    return () => { this.observers.delete(observer) }
  }

  emit(event: CoreEvent): void {
    for (const observer of [...this.observers]) {
      // Observe completed state transitions; never reenter one halfway through.
      queueMicrotask(() => {
        if (!this.observers.has(observer)) return
        try {
          Promise.resolve(observer(event)).catch(error => this.report(error))
        } catch (error) { this.report(error) }
      })
    }
  }

  clear(): void { this.observers.clear() }

  private report(error: unknown): void {
    try { Promise.resolve(this.onError?.(asError(error))).catch(() => {}) } catch { /* Observer reporting cannot own runtime control. */ }
  }
}
