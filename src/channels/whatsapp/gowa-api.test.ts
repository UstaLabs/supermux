import { describe, expect, test } from "bun:test"
import { GowaClient } from "./gowa-api"

function fakeFetch(captured: any[], response: any): typeof fetch {
  return (async (url: any, init?: any) => {
    captured.push({ url: String(url), init })
    return new Response(JSON.stringify(response), { status: 200, headers: { "content-type": "application/json" } })
  }) as unknown as typeof fetch
}

describe("GowaClient.sendText", () => {
  test("POSTs JSON to /send/message with basic auth + device header and returns message_id", async () => {
    const cap: any[] = []
    const c = new GowaClient({ baseUrl: "http://127.0.0.1:3000", basicAuth: "u:p", deviceId: "dev1", fetchImpl: fakeFetch(cap, { results: { message_id: "ABC123" } }) })
    const r = await c.sendText("628000@s.whatsapp.net", "hi", "REPLY1")
    expect(r.message_id).toBe("ABC123")
    expect(cap[0].url).toBe("http://127.0.0.1:3000/send/message")
    expect(cap[0].init.method).toBe("POST")
    expect(cap[0].init.headers["Authorization"]).toBe("Basic " + Buffer.from("u:p").toString("base64"))
    expect(cap[0].init.headers["X-Device-Id"]).toBe("dev1")
    expect(JSON.parse(cap[0].init.body)).toEqual({ phone: "628000@s.whatsapp.net", message: "hi", reply_message_id: "REPLY1" })
  })
})

describe("GowaClient.status / fetchMedia", () => {
  test("status maps results flags", async () => {
    const c = new GowaClient({ baseUrl: "http://h:3000", fetchImpl: fakeFetch([], { results: { is_connected: true, is_logged_in: true } }) })
    expect(await c.status()).toEqual({ is_connected: true, is_logged_in: true })
  })
  test("fetchMedia resolves a relative statics path against baseUrl", async () => {
    const cap: any[] = []
    const f = ((async (url: any) => { cap.push(String(url)); return new Response(new Uint8Array([1, 2, 3])) }) as unknown) as typeof fetch
    const c = new GowaClient({ baseUrl: "http://h:3000", fetchImpl: f })
    const bytes = await c.fetchMedia("statics/media/x.ogg")
    expect(Array.from(bytes)).toEqual([1, 2, 3])
    expect(cap[0]).toBe("http://h:3000/statics/media/x.ogg")
  })
})
