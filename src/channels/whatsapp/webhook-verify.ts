import { createHmac, timingSafeEqual } from "crypto"

// GOWA signs each webhook with HMAC-SHA256 over the raw request body, sent as
// `X-Hub-Signature-256: sha256=<hexdigest>`. Verify over the EXACT received
// bytes (never a re-serialized JSON) or the signature won't match.
export function verifyGowaSignature(rawBody: string, header: string | null | undefined, secret: string): boolean {
  if (!header) return false
  const expected = createHmac("sha256", secret).update(rawBody, "utf8").digest("hex")
  const received = header.startsWith("sha256=") ? header.slice("sha256=".length) : header
  let a: Buffer
  let b: Buffer
  try {
    a = Buffer.from(expected, "hex")
    b = Buffer.from(received, "hex")
  } catch {
    return false
  }
  if (a.length === 0 || a.length !== b.length) return false
  return timingSafeEqual(a, b)
}
