import type { DeviceStore } from "./device-store"

// Native pairing: the deep link carries the raw device token (minted by the
// CLI). We validate it and echo it back as JSON so the app can store it in the
// Keychain/Keystore. No cookie is set (native has no cookie jar for this).
export function pairJsonResponse(token: string, store: DeviceStore): Response {
  if (!token) return new Response("unauthorized", { status: 401 })
  const dev = store.verify(token)
  if (!dev) return new Response("unauthorized", { status: 401 })
  return Response.json({ token, name: dev.name })
}
