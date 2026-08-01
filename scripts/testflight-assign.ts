// scripts/testflight-assign.ts
// usage:
//   bun scripts/testflight-assign.ts <build-number>
//
// Required env (same App Store Connect key trio the release workflow uses for the upload):
//   ASC_API_KEY_P8_BASE64   base64 of the AuthKey_<id>.p8
//   ASC_API_KEY_ID          the key id (kid)
//   ASC_API_ISSUER_ID       the issuer id
// Optional env:
//   TESTFLIGHT_BUNDLE_ID    app to look up (default dev.supermux.app)
//   TESTFLIGHT_GROUP        internal beta group to attach to (default "Smoke Test")
//   TESTFLIGHT_ASSIGN_TIMEOUT_SEC   how long to wait for processing (default 1800)
//
// Waits for the build App Store Connect just received to finish PROCESSING, then attaches it
// to the internal tester group. `altool --upload-app` alone does NOT make a build installable:
// a VALID build that belongs to no beta group never appears in anyone's TestFlight app. This
// is the step that ends the upload → actually-on-your-phone gap.
//
// Matching is by the EXACT CFBundleVersion string. ASC sorts `version` lexically ("9" > "10"),
// so "take the newest build" is wrong — we filter for the one number this run uploaded.

const buildNumber = process.argv[2]
if (!buildNumber) {
  console.error("usage: testflight-assign.ts <build-number>")
  process.exit(1)
}

const keyId = requireEnv("ASC_API_KEY_ID")
const issuerId = requireEnv("ASC_API_ISSUER_ID")
const p8Base64 = requireEnv("ASC_API_KEY_P8_BASE64")
const bundleId = process.env.TESTFLIGHT_BUNDLE_ID || "dev.supermux.app"
const groupName = process.env.TESTFLIGHT_GROUP || "Smoke Test"
const timeoutSec = Number(process.env.TESTFLIGHT_ASSIGN_TIMEOUT_SEC || 1800)
const pollIntervalMs = 20_000

function requireEnv(name: string): string {
  const v = process.env[name]
  if (!v) {
    console.error(`missing required env ${name}`)
    process.exit(1)
  }
  return v
}

function base64url(bytes: Uint8Array): string {
  return Buffer.from(bytes).toString("base64").replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "")
}

// ES256 JWT signed with the ASC key. WebCrypto's ECDSA output is already the raw r||s pair
// JOSE wants, so no DER unwrapping is needed.
async function mintToken(): Promise<string> {
  const pem = Buffer.from(p8Base64, "base64").toString("utf8")
  const der = Buffer.from(pem.replace(/-----[^-]+-----/g, "").replace(/\s+/g, ""), "base64")
  const key = await crypto.subtle.importKey(
    "pkcs8",
    der,
    { name: "ECDSA", namedCurve: "P-256" },
    false,
    ["sign"],
  )
  const now = Math.floor(Date.now() / 1000)
  const header = base64url(Buffer.from(JSON.stringify({ alg: "ES256", kid: keyId, typ: "JWT" })))
  const payload = base64url(
    Buffer.from(JSON.stringify({ iss: issuerId, iat: now, exp: now + 900, aud: "appstoreconnect-v1" })),
  )
  const signingInput = `${header}.${payload}`
  const sig = await crypto.subtle.sign(
    { name: "ECDSA", hash: "SHA-256" },
    key,
    Buffer.from(signingInput),
  )
  return `${signingInput}.${base64url(new Uint8Array(sig))}`
}

type ApiResult = { status: number; body: any }

async function api(method: string, path: string, body?: unknown): Promise<ApiResult> {
  // The token is minted per call: polling can outlive a single token's 15-minute life.
  const res = await fetch(`https://api.appstoreconnect.apple.com${path}`, {
    method,
    headers: {
      authorization: `Bearer ${await mintToken()}`,
      "content-type": "application/json",
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  const text = await res.text()
  return { status: res.status, body: text ? JSON.parse(text) : {} }
}

function fail(message: string, result?: ApiResult): never {
  console.error(message)
  if (result) console.error(`HTTP ${result.status} ${JSON.stringify(result.body).slice(0, 600)}`)
  process.exit(1)
}

const appsRes = await api("GET", `/v1/apps?filter%5BbundleId%5D=${encodeURIComponent(bundleId)}&limit=1`)
if (appsRes.status !== 200) fail(`could not look up app ${bundleId}`, appsRes)
const appId: string | undefined = appsRes.body.data?.[0]?.id
if (!appId) fail(`no App Store Connect app for bundle id ${bundleId}`)
console.log(`app ${bundleId} = ${appId}`)

const groupsRes = await api("GET", `/v1/apps/${appId}/betaGroups?limit=200`)
if (groupsRes.status !== 200) fail("could not list beta groups", groupsRes)
const groups: any[] = groupsRes.body.data ?? []
const group = groups.find((g) => g.attributes?.name?.toLowerCase() === groupName.toLowerCase())
if (!group) {
  fail(
    `no beta group named "${groupName}" (found: ${groups.map((g) => g.attributes?.name).join(", ") || "none"}).` +
      " Create it in App Store Connect, or set TESTFLIGHT_GROUP.",
  )
}
console.log(`group "${group.attributes.name}" = ${group.id} (internal=${group.attributes.isInternalGroup})`)

// A build stays PROCESSING for several minutes after the upload returns; it cannot be attached
// until it is VALID. Poll for this exact build number rather than whatever is newest.
const deadline = Date.now() + timeoutSec * 1000
let buildId: string | undefined
while (true) {
  const res = await api(
    "GET",
    `/v1/builds?filter%5Bapp%5D=${appId}&filter%5Bversion%5D=${encodeURIComponent(buildNumber)}&limit=1`,
  )
  if (res.status !== 200) fail(`could not query build ${buildNumber}`, res)
  const build = res.body.data?.[0]
  const state: string | undefined = build?.attributes?.processingState
  if (state === "VALID") {
    buildId = build.id
    console.log(`build ${buildNumber} is VALID (${buildId})`)
    break
  }
  if (state === "FAILED" || state === "INVALID") {
    fail(`build ${buildNumber} finished processing as ${state} — nothing to attach`)
  }
  if (Date.now() >= deadline) {
    fail(`build ${buildNumber} still ${state ?? "not visible"} after ${timeoutSec}s — giving up`)
  }
  console.log(`build ${buildNumber} is ${state ?? "not visible yet"}; waiting…`)
  await new Promise((r) => setTimeout(r, pollIntervalMs))
}

const attach = await api("POST", `/v1/betaGroups/${group.id}/relationships/builds`, {
  data: [{ type: "builds", id: buildId }],
})
// 409 = already attached (a re-run of this job), which is the state we wanted anyway.
if (attach.status !== 204 && attach.status !== 200 && attach.status !== 409) {
  fail(`could not attach build ${buildNumber} to "${group.attributes.name}"`, attach)
}
console.log(
  attach.status === 409
    ? `build ${buildNumber} was already on "${group.attributes.name}"`
    : `attached build ${buildNumber} to "${group.attributes.name}" — testers can install it now`,
)
