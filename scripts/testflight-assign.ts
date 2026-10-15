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
//   TESTFLIGHT_GROUP        beta group(s) to attach to, comma-separated (default "Smoke Test")
//   TESTFLIGHT_WHATS_NEW    "What to Test" text (required by Apple for external groups)
//   TESTFLIGHT_ASSIGN_TIMEOUT_SEC   how long to wait for processing (default 1800)
//
// Waits for the build App Store Connect just received to finish PROCESSING, then attaches it
// to every named tester group. `altool --upload-app` alone does NOT make a build installable:
// a VALID build that belongs to no beta group never appears in anyone's TestFlight app. This
// is the step that ends the upload → actually-on-your-phone gap.
//
// Matching is by the EXACT CFBundleVersion string. ASC sorts `version` lexically ("9" > "10"),
// so "take the newest build" is wrong — we filter for the one number this run uploaded.
//
// Internal vs external groups differ in one big way, and that difference is the whole reason
// this script grew past a single attach call:
//   - INTERNAL group ("Smoke Test"): attach and the testers have it. No Apple review, ever.
//   - EXTERNAL group ("Developer Beta", public link): Apple gates it behind Beta App Review,
//     and refuses the submission unless the build carries a "What to Test" note. So for any
//     external target we set the note FIRST, attach, then file the review submission. Only the
//     first build of a MARKETING_VERSION train is really reviewed; later builds on the same
//     train normally clear immediately, which is what keeps fast iteration fast.

import { ascFromEnv, fail } from "./lib/asc"

const buildNumber = process.argv[2]
if (!buildNumber) {
  console.error("usage: testflight-assign.ts <build-number>")
  process.exit(1)
}

const asc = ascFromEnv()
const bundleId = process.env.TESTFLIGHT_BUNDLE_ID || "dev.supermux.app"
const groupNames = (process.env.TESTFLIGHT_GROUP || "Smoke Test")
  .split(",")
  .map((s) => s.trim())
  .filter(Boolean)
const whatsNewOverride = process.env.TESTFLIGHT_WHATS_NEW?.trim()
const timeoutSec = Number(process.env.TESTFLIGHT_ASSIGN_TIMEOUT_SEC || 1800)
const pollIntervalMs = 20_000

if (groupNames.length === 0) fail("TESTFLIGHT_GROUP resolved to no group names")

const appId = await asc.appId(bundleId)
console.log(`app ${bundleId} = ${appId}`)

const groupsRes = await asc.api("GET", `/v1/apps/${appId}/betaGroups?limit=200`)
if (groupsRes.status !== 200) fail("could not list beta groups", groupsRes)
const allGroups: any[] = groupsRes.body.data ?? []

const targets = groupNames.map((name) => {
  const group = allGroups.find((g) => g.attributes?.name?.toLowerCase() === name.toLowerCase())
  if (!group) {
    fail(
      `no beta group named "${name}" (found: ${allGroups.map((g) => g.attributes?.name).join(", ") || "none"}).` +
        " Create it with scripts/testflight-external-setup.ts or in App Store Connect, or set TESTFLIGHT_GROUP.",
    )
  }
  return group
})
for (const g of targets) {
  console.log(`group "${g.attributes.name}" = ${g.id} (internal=${g.attributes.isInternalGroup})`)
}
const externalTargets = targets.filter((g) => g.attributes?.isInternalGroup === false)

// A build stays PROCESSING for several minutes after the upload returns; it cannot be attached
// until it is VALID. Poll for this exact build number rather than whatever is newest.
const deadline = Date.now() + timeoutSec * 1000
let buildId: string | undefined
while (true) {
  const res = await asc.api(
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

// Apple rejects an external beta submission whose build has no "What to Test" localization, so
// this runs before the attach rather than after it. Internal-only runs skip it: the note is
// optional there, and writing one would be a pointless extra call on every build.
if (externalTargets.length > 0) {
  const locRes = await asc.api("GET", `/v1/builds/${buildId}/betaBuildLocalizations?limit=200`)
  if (locRes.status !== 200) fail(`could not list "What to Test" localizations for build ${buildNumber}`, locRes)
  const existing = (locRes.body.data ?? []).find((l: any) => l.attributes?.locale === "en-US")
  // Re-running the job must not clobber a hand-written note with the generic fallback, so an
  // existing note is only replaced when this run was given an explicit one.
  if (existing && !whatsNewOverride) {
    console.log(
      `"What to Test" already set on build ${buildNumber} — leaving it (set TESTFLIGHT_WHATS_NEW to replace)`,
    )
  } else {
    const whatsNew = whatsNewOverride || `Build ${buildNumber}`
    const write = existing
      ? await asc.api("PATCH", `/v1/betaBuildLocalizations/${existing.id}`, {
          data: { type: "betaBuildLocalizations", id: existing.id, attributes: { whatsNew } },
        })
      : await asc.api("POST", "/v1/betaBuildLocalizations", {
          data: {
            type: "betaBuildLocalizations",
            attributes: { locale: "en-US", whatsNew },
            relationships: { build: { data: { type: "builds", id: buildId } } },
          },
        })
    if (write.status !== 200 && write.status !== 201) {
      fail(`could not set "What to Test" on build ${buildNumber}`, write)
    }
    console.log(`"What to Test" set on build ${buildNumber}: ${whatsNew.split("\n")[0]}`)
  }
}

for (const group of targets) {
  const attach = await asc.api("POST", `/v1/betaGroups/${group.id}/relationships/builds`, {
    data: [{ type: "builds", id: buildId }],
  })
  // 409 = already attached (a re-run of this job), which is the state we wanted anyway.
  if (attach.status !== 204 && attach.status !== 200 && attach.status !== 409) {
    fail(`could not attach build ${buildNumber} to "${group.attributes.name}"`, attach)
  }
  console.log(
    attach.status === 409
      ? `build ${buildNumber} was already on "${group.attributes.name}"`
      : `attached build ${buildNumber} to "${group.attributes.name}"${
          group.attributes.isInternalGroup ? " — testers can install it now" : " — pending beta review"
        }`,
  )
}

// External testers only see the build once Apple clears it. Re-submitting a build that is already
// in the queue is rejected with 422 INVALID_QC_STATE (not the 409 you would expect), so rather than
// pattern-matching Apple's error codes, any rejection is resolved by asking what submission the
// build actually has: one already on file means the re-run did its job. No submission at all is
// worth failing the job over — the internal group already has the build, so a red run means "the
// public lane is stuck", which is exactly the thing you want to find out about immediately.
if (externalTargets.length > 0) {
  const submit = await asc.api("POST", "/v1/betaAppReviewSubmissions", {
    data: {
      type: "betaAppReviewSubmissions",
      relationships: { build: { data: { type: "builds", id: buildId } } },
    },
  })
  if (submit.status === 201) {
    console.log(`build ${buildNumber} submitted for Beta App Review`)
  } else {
    const existing = await asc.api(
      "GET",
      `/v1/betaAppReviewSubmissions?filter%5Bbuild%5D=${encodeURIComponent(buildId)}`,
    )
    const state: string | undefined = existing.body.data?.[0]?.attributes?.betaReviewState
    if (!state) {
      fail(
        `could not submit build ${buildNumber} for Beta App Review.` +
          " If this complains about missing review details or a beta app description," +
          " run scripts/testflight-external-setup.ts once.",
        submit,
      )
    }
    console.log(`build ${buildNumber} is already in Beta App Review (${state})`)
  }

  for (const group of externalTargets) {
    const link = group.attributes?.publicLink
    if (link) console.log(`public TestFlight link for "${group.attributes.name}": ${link}`)
  }
}
