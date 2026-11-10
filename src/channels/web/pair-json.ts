import type { DeviceStore, PairingClaim } from "./device-store"

/** What a pairing screen says for a link that no longer pairs (#119); #97's sheet promises "works once, expires in 10 min". */
export function pairingRefusal(reason: Exclude<PairingClaim, { ok: true }>["reason"]): { error: string; code: string } {
  if (reason === "used") return { error: "This code was already used. Make a new one.", code: "pairing_used" }
  return { error: "This code has expired. Make a new one.", code: "pairing_expired" }
}

// Native pairing: the deep link carries the raw device token. Its first use claims the pending device (#119:
// once, within 10 minutes) and echoes the token back as JSON so the app can store it in the Keychain/Keystore.
// No cookie is set (native has no cookie jar for this).
export function pairJsonResponse(token: string, store: Pick<DeviceStore, "claimPairing">): Response {
  if (!token) return new Response("unauthorized", { status: 401 })
  const claim = store.claimPairing(token)
  if (!claim.ok) return Response.json(pairingRefusal(claim.reason), { status: 401 })
  return Response.json({ token, name: claim.name })
}
