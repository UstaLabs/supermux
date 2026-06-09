export interface WebEnvResult {
  enabled: boolean
  error?: string
}

/** Validate the MUX_WEB_PORT / MUX_WEB_PUBLIC_URL pair. Both-or-neither; port and URL well-formed. */
export function validateWebEnv(port: string | undefined, publicUrl: string | undefined): WebEnvResult {
  const hasPort = port !== undefined && port !== ""
  const hasUrl = publicUrl !== undefined && publicUrl !== ""

  if (!hasPort && !hasUrl) return { enabled: false }
  if (hasPort !== hasUrl) {
    return { enabled: false, error: "MUX_WEB_PORT and MUX_WEB_PUBLIC_URL must be set together — got only one. Set both to enable the web channel, or neither to disable it." }
  }

  const p = Number(port)
  if (!Number.isInteger(p) || p <= 0 || p > 65535) {
    return { enabled: false, error: `MUX_WEB_PORT must be an integer 1–65535 (got "${port}").` }
  }
  try {
    new URL(publicUrl!)
  } catch {
    return { enabled: false, error: `MUX_WEB_PUBLIC_URL is not a valid URL (got "${publicUrl}").` }
  }
  return { enabled: true }
}
