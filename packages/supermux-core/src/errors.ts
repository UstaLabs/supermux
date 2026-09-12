export class CoreError extends Error {
  constructor(readonly code: string, message: string, options?: ErrorOptions) {
    super(message, options)
    this.name = "CoreError"
  }
}

export class UnsupportedOperation extends CoreError {
  constructor(operation: string, agent: string) {
    super("unsupported_operation", `${agent} does not support ${operation}`)
    this.name = "UnsupportedOperation"
  }
}

export function asError(error: unknown): Error {
  return error instanceof Error ? error : new Error(String(error))
}
