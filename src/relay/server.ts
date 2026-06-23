import type { RelayCore } from "./core"

const json = (b: any, status = 200) =>
  new Response(JSON.stringify(b), { status, headers: { "content-type": "application/json" } })

export function makeRelayHandler(core: RelayCore) {
  return async (req: Request): Promise<Response> => {
    const { pathname } = new URL(req.url)
    if (req.method !== "POST") return new Response("method", { status: 405 })
    let b: any
    try { b = await req.json() } catch { return json({ error: "bad json" }, 400) }
    if (pathname === "/register") {
      if (b?.platform !== "ios" && b?.platform !== "android") return json({ error: "platform" }, 400)
      if (typeof b?.pushToken !== "string") return json({ error: "pushToken" }, 400)
      await core.register(b.platform, b.pushToken)
      return json({ status: "pending" }, 202)
    }
    if (pathname === "/push") {
      if (typeof b?.routingToken !== "string" || typeof b?.ciphertext !== "string") return json({ error: "fields" }, 400)
      return json(await core.push(b.routingToken, b.ciphertext))
    }
    if (pathname === "/unregister") {
      if (typeof b?.routingToken !== "string") return json({ error: "routingToken" }, 400)
      core.unregister(b.routingToken)
      return json({ ok: true })
    }
    return new Response("not found", { status: 404 })
  }
}
