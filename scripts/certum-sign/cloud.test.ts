// #116: our own Certum SimplySign cloud client: log in (e-mail + one-time code), fetch the signing certificate, sign
// a SHA-256 digest. Driven here against a scripted fake of the cloud: every request is checked, and nothing it builds
// may reach another host or carry the code, the token or the e-mail into an error.
import { expect, test } from "bun:test"
import { CERTUM, CloudError, login, requestSignature, signingCard, type Fetch, type Reply } from "./cloud"

const EMAIL = "dev@example.com"
const CODE = "123456"
const TOKEN = "tok-abcdefghijklmnop"
const CERT_PEM = "-----BEGIN CERTIFICATE-----\nMIIB\n-----END CERTIFICATE-----\n"
const DIGEST = Buffer.alloc(32, 7)
const SIGNATURE_HEX = "ab".repeat(384)

interface Seen { url: URL; method: string; headers: Record<string, string>; body: string }

/** A fake cloud: [route] answers each request; every request is recorded. */
function fakeCloud(route: (r: Seen) => Reply): { fetch: Fetch; seen: Seen[] } {
  const seen: Seen[] = []
  const fetch: Fetch = async (url, init) => {
    const r: Seen = {
      url: new URL(url), method: init.method ?? "GET", headers: Object.fromEntries(Object.entries(init.headers ?? {}).map(([k, v]) => [k.toLowerCase(), v])),
      body: init.body === undefined ? "" : Buffer.from(init.body as Uint8Array | string).toString("latin1"),
    }
    seen.push(r)
    return route(r)
  }
  return { fetch, seen }
}

const reply = (status: number, body = "", headers: Record<string, string> = {}): Reply => ({ status, headers, body: Buffer.from(body, "latin1") })
const LOGIN_PAGE = `<form><input type="hidden" name="execution" value="e1s1&amp;x"/><input name="lt" value="LT-1"/></form>`

function loginRoutes(r: Seen): Reply | null {
  const p = r.url.pathname
  if (p === "/idp/oauth2.0/authorize") return reply(302, "", { location: "/idp/login?service=svc-1", "set-cookie": "JSESSIONID=cookie-value-0123456789; Path=/" })
  if (p === "/idp/login" && r.method === "GET") return reply(200, LOGIN_PAGE)
  if (p === "/idp/login" && r.method === "POST") return reply(302, "", { location: `${CERTUM.redirectUri}?code=OC-42` })
  if (p === "/idp/oauth2.0/accessToken") return reply(200, JSON.stringify({ access_token: TOKEN, expires_in: 1800 }), { "content-type": "application/json" })
  return null
}

test("login: the OAuth authorize, the CAS form with the e-mail and the one-time code, then the token", async () => {
  const cloud = fakeCloud((r) => loginRoutes(r) ?? reply(404))
  const session = await login(cloud.fetch, EMAIL, CODE)
  expect(session.token).toBe(TOKEN)
  const [authorize, page, form, token] = cloud.seen
  expect(authorize!.url.searchParams.get("response_type")).toBe("code")
  expect(authorize!.url.searchParams.get("client_id")).toBe(CERTUM.clientId)
  expect(authorize!.url.searchParams.get("redirect_uri")).toBe(CERTUM.redirectUri)
  expect(page!.headers["cookie"]).toContain("JSESSIONID=cookie-value-0123456789")
  const fields = new URLSearchParams(form!.body)
  expect([fields.get("username"), fields.get("password"), fields.get("execution"), fields.get("lt")]).toEqual([EMAIL, CODE, "e1s1&x", "LT-1"])
  expect(form!.url.searchParams.get("service")).toBe("svc-1")
  expect(token!.url.searchParams.get("code")).toBe("OC-42")
  expect(token!.url.searchParams.get("grant_type")).toBe("authorization_code")
  // The redirect target that carries the code is never requested.
  expect(cloud.seen.some((s) => s.url.pathname === "/redirect")).toBe(false)
  expect(cloud.seen.every((s) => s.url.origin === "https://cloudsign.webnotarius.pl")).toBe(true)
})

test("a refused code is said plainly, without the code, the e-mail or the page", async () => {
  const cloud = fakeCloud((r) => r.url.pathname === "/idp/login" && r.method === "POST" ? reply(200, `<div class="errors">bad</div>`) : loginRoutes(r) ?? reply(404))
  const error = await login(cloud.fetch, EMAIL, CODE).catch((e) => e)
  expect(error).toBeInstanceOf(CloudError)
  expect(error.refused).toBe(true)
  for (const secret of [CODE, EMAIL, "OC-42", TOKEN]) expect(String(error.message)).not.toContain(secret)
})

test("a redirect to another host is refused, never followed", async () => {
  const cloud = fakeCloud((r) => r.url.pathname === "/idp/oauth2.0/authorize" ? reply(302, "", { location: "https://evil.example/steal" }) : reply(404))
  const error = await login(cloud.fetch, EMAIL, CODE).catch((e) => e)
  expect(String(error)).toContain("another host")
  expect(cloud.seen.map((s) => s.url.host)).toEqual(["cloudsign.webnotarius.pl"])
})

/** The SCS task protocol: POST → 202 with an atom:link to poll → the result. */
function scsRoutes(r: Seen): Reply | null {
  const p = r.url.pathname
  if (r.headers["authorization"] !== `Bearer ${TOKEN}`) return reply(401)
  if (p === "/card/v1/cards/tasks") return reply(202, JSON.stringify({ "atom:link": "/card/v1/poll/1", "ping-after": 1 }), { "content-type": "application/json" })
  if (p === "/card/v1/poll/1") return reply(200, JSON.stringify([{ cardno: "CARD-1", label: "Open Source", pinrequired: false }]), { "content-type": "application/json" })
  if (p === "/card/v1/cards/CARD-1/certificates/tasks") {
    return reply(200, `--b\r\nContent-Disposition: form-data; name="certificate"\r\n\r\n${CERT_PEM}\r\n--b--\r\n`, { "content-type": "multipart/form-data; boundary=b" })
  }
  if (p === "/card/v1/cards/CARD-1/certificates/signature") {
    return reply(200, JSON.stringify([{ [DIGEST.toString("hex")]: SIGNATURE_HEX }]), { "content-type": "application/json" })
  }
  return null
}

test("the signing card and its certificate, through the task protocol's polling", async () => {
  const cloud = fakeCloud((r) => scsRoutes(r) ?? reply(404))
  const card = await signingCard(cloud.fetch, { token: TOKEN })
  expect(card.serial).toBe("CARD-1")
  expect(card.certificatePem.toString()).toBe(CERT_PEM)
  expect(cloud.seen.map((s) => s.url.pathname)).toEqual(["/card/v1/cards/tasks", "/card/v1/poll/1", "/card/v1/cards/CARD-1/certificates/tasks"])
})

test("signing a digest sends its hex and the certificate, and returns the RSA signature", async () => {
  const cloud = fakeCloud((r) => scsRoutes(r) ?? reply(404))
  const signature = await requestSignature(cloud.fetch, { token: TOKEN }, { serial: "CARD-1", certificatePem: Buffer.from(CERT_PEM) }, DIGEST)
  expect(signature.toString("hex")).toBe(SIGNATURE_HEX)
  const sent = cloud.seen.at(-1)!
  expect(sent.headers["content-type"]).toMatch(/^multipart\/form-data; boundary=/)
  expect(sent.body).toContain(`"digests":["${DIGEST.toString("hex")}"]`)
  expect(sent.body).toContain(`"digesttype":"SHA256"`)
  expect(sent.body).toContain(CERT_PEM)
})

test("only a SHA-256 digest is sent, and a missing signature is an error", async () => {
  const cloud = fakeCloud((r) => r.url.pathname.endsWith("/signature") ? reply(200, "[{}]", { "content-type": "application/json" }) : reply(404))
  const card = { serial: "CARD-1", certificatePem: Buffer.from(CERT_PEM) }
  await expect(requestSignature(cloud.fetch, { token: TOKEN }, card, Buffer.alloc(20))).rejects.toThrow(/SHA-256/)
  const error = await requestSignature(cloud.fetch, { token: TOKEN }, card, DIGEST).catch((e) => e)
  expect(String(error)).toContain("no signature")
  expect(String(error)).not.toContain(TOKEN)
})

test("a poll link to another host is refused", async () => {
  const cloud = fakeCloud((r) => r.url.pathname === "/card/v1/cards/tasks" ? reply(202, JSON.stringify({ "atom:link": "https://evil.example/poll" }), { "content-type": "application/json" }) : reply(404))
  await expect(signingCard(cloud.fetch, { token: TOKEN })).rejects.toThrow(/another host/)
})
