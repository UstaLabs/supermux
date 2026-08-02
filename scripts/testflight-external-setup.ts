// scripts/testflight-external-setup.ts
// usage:
//   bun scripts/testflight-external-setup.ts
//
// Required env: the ASC key trio (see scripts/lib/asc.ts).
// Optional env:
//   TESTFLIGHT_BUNDLE_ID          app to configure (default dev.supermux.app)
//   TESTFLIGHT_EXTERNAL_GROUP     group name to create/repair (default "Developer Beta")
//   TESTFLIGHT_PUBLIC_LINK_LIMIT  cap on self-serve joins (default: uncapped, Apple's own 10k ceiling applies)
//
// One-time (idempotent) setup for the EXTERNAL TestFlight lane — the one strangers can join from
// a link without you collecting their Apple ID or UDID. Three things have to be true before Apple
// will let an external build out, and none of them is something the per-build assign script should
// be doing on every run:
//
//   1. The app needs Beta App Review contact details. Without them every submission is rejected.
//      Rather than hardcode a phone number and reviewer notes into the repo, this mirrors what
//      the most recent App Store version already had reviewed and approved — same contact, same
//      "how to test" notes, same demo credentials. One source of truth, nothing personal in git.
//   2. The app needs a Beta App Description + feedback email, which is a SEPARATE record from the
//      App Store description (submission fails `MISSING_BETA_APP_DESCRIPTION` without it). Mirrored
//      from the live listing for the same reason.
//   3. The external group has to exist with its public link switched on.
//
// Re-running is safe and is the repair path: it re-mirrors everything and turns the public link
// back on if it ever got disabled.

import { ascFromEnv, fail } from "./lib/asc"

const asc = ascFromEnv()
const bundleId = process.env.TESTFLIGHT_BUNDLE_ID || "dev.supermux.app"
const groupName = process.env.TESTFLIGHT_EXTERNAL_GROUP || "Developer Beta"
const publicLinkLimit = process.env.TESTFLIGHT_PUBLIC_LINK_LIMIT
  ? Number(process.env.TESTFLIGHT_PUBLIC_LINK_LIMIT)
  : undefined
if (publicLinkLimit !== undefined && !Number.isFinite(publicLinkLimit)) {
  fail(`TESTFLIGHT_PUBLIC_LINK_LIMIT is not a number: ${process.env.TESTFLIGHT_PUBLIC_LINK_LIMIT}`)
}

const appId = await asc.appId(bundleId)
console.log(`app ${bundleId} = ${appId}`)

// ---- 1. Beta App Review details, mirrored from the last reviewed App Store version ----------

const versionsRes = await asc.api("GET", `/v1/apps/${appId}/appStoreVersions?limit=10`)
if (versionsRes.status !== 200) fail("could not list App Store versions", versionsRes)

let source: any | undefined
let sourceVersion: string | undefined
for (const version of versionsRes.body.data ?? []) {
  const detail = await asc.api("GET", `/v1/appStoreVersions/${version.id}/appStoreReviewDetail`)
  if (detail.status === 200 && detail.body.data?.attributes?.contactEmail) {
    source = detail.body.data.attributes
    sourceVersion = version.attributes?.versionString
    break
  }
}
if (!source) {
  fail(
    "no App Store version carries review contact details to mirror — fill in App Store Connect →" +
      " TestFlight → Test Information (contact name, email, phone) once, then re-run.",
  )
}

const betaDetailRes = await asc.api("GET", `/v1/apps/${appId}/betaAppReviewDetail`)
if (betaDetailRes.status !== 200) fail("could not read the beta app review detail", betaDetailRes)
const betaDetailId: string = betaDetailRes.body.data.id

const patch = await asc.api("PATCH", `/v1/betaAppReviewDetails/${betaDetailId}`, {
  data: {
    type: "betaAppReviewDetails",
    id: betaDetailId,
    attributes: {
      contactFirstName: source.contactFirstName,
      contactLastName: source.contactLastName,
      contactPhone: source.contactPhone,
      contactEmail: source.contactEmail,
      demoAccountName: source.demoAccountName,
      demoAccountPassword: source.demoAccountPassword,
      demoAccountRequired: source.demoAccountRequired ?? false,
      notes: source.notes,
    },
  },
})
if (patch.status !== 200) fail("could not write the beta app review detail", patch)
console.log(
  `beta review details mirrored from App Store version ${sourceVersion}` +
    ` (contact ${source.contactEmail}, ${source.notes ? `${source.notes.length} chars of notes` : "no notes"})`,
)

// ---- 2. Beta App Description + feedback email, mirrored from the live listing ----------------

// Apple keeps a separate "what is this beta" record from the App Store description, and refuses
// an external submission without it. Same mirroring trick: reuse the copy that already shipped.
let listing: any | undefined
for (const version of versionsRes.body.data ?? []) {
  const locs = await asc.api("GET", `/v1/appStoreVersions/${version.id}/appStoreVersionLocalizations?limit=50`)
  if (locs.status !== 200) continue
  const enUs = (locs.body.data ?? []).find((l: any) => l.attributes?.locale === "en-US" && l.attributes?.description)
  if (enUs) {
    listing = enUs.attributes
    break
  }
}
if (!listing) fail("no App Store version carries an en-US description to mirror into the beta description")

// The privacy policy URL lives on the app-info record rather than the version record.
let privacyPolicyUrl: string | undefined
const appInfos = await asc.api("GET", `/v1/apps/${appId}/appInfos?limit=10`)
for (const info of appInfos.body.data ?? []) {
  const locs = await asc.api("GET", `/v1/appInfos/${info.id}/appInfoLocalizations?limit=50`)
  const enUs = (locs.body.data ?? []).find((l: any) => l.attributes?.locale === "en-US")
  if (enUs?.attributes?.privacyPolicyUrl) {
    privacyPolicyUrl = enUs.attributes.privacyPolicyUrl
    break
  }
}

const betaLocAttributes = {
  // Apple caps the beta description at 4000 characters, same as the store description, so this
  // slice is belt-and-braces rather than an expected truncation.
  description: String(listing.description).slice(0, 4000),
  feedbackEmail: source.contactEmail,
  marketingUrl: listing.marketingUrl ?? undefined,
  privacyPolicyUrl,
}

const betaLocsRes = await asc.api("GET", `/v1/apps/${appId}/betaAppLocalizations?limit=50`)
if (betaLocsRes.status !== 200) fail("could not list beta app localizations", betaLocsRes)
const existingBetaLoc = (betaLocsRes.body.data ?? []).find((l: any) => l.attributes?.locale === "en-US")
const betaLocWrite = existingBetaLoc
  ? await asc.api("PATCH", `/v1/betaAppLocalizations/${existingBetaLoc.id}`, {
      data: { type: "betaAppLocalizations", id: existingBetaLoc.id, attributes: betaLocAttributes },
    })
  : await asc.api("POST", "/v1/betaAppLocalizations", {
      data: {
        type: "betaAppLocalizations",
        attributes: { locale: "en-US", ...betaLocAttributes },
        relationships: { app: { data: { type: "apps", id: appId } } },
      },
    })
if (betaLocWrite.status !== 200 && betaLocWrite.status !== 201) {
  fail("could not write the beta app description", betaLocWrite)
}
console.log(
  `beta app description mirrored from the store listing (${betaLocAttributes.description.length} chars,` +
    ` feedback to ${betaLocAttributes.feedbackEmail})`,
)

// ---- 3. The external group with its public link ---------------------------------------------

const groupsRes = await asc.api("GET", `/v1/apps/${appId}/betaGroups?limit=200`)
if (groupsRes.status !== 200) fail("could not list beta groups", groupsRes)
const existing = (groupsRes.body.data ?? []).find(
  (g: any) => g.attributes?.name?.toLowerCase() === groupName.toLowerCase(),
)

// Apple decides internal-vs-external at creation: groups made through this endpoint are always
// external, and `isInternalGroup` is read-only. So an existing INTERNAL group with this name is
// a dead end rather than something to patch — say so instead of silently doing nothing useful.
if (existing?.attributes?.isInternalGroup) {
  fail(
    `beta group "${existing.attributes.name}" already exists but is INTERNAL, and Apple does not allow` +
      " converting it. Pick a different TESTFLIGHT_EXTERNAL_GROUP name.",
  )
}

const attributes: Record<string, unknown> = {
  publicLinkEnabled: true,
  feedbackEnabled: true,
  publicLinkLimitEnabled: publicLinkLimit !== undefined,
}
if (publicLinkLimit !== undefined) attributes.publicLinkLimit = publicLinkLimit

let group: any
if (existing) {
  const update = await asc.api("PATCH", `/v1/betaGroups/${existing.id}`, {
    data: { type: "betaGroups", id: existing.id, attributes },
  })
  if (update.status !== 200) fail(`could not update beta group "${groupName}"`, update)
  group = update.body.data
  console.log(`beta group "${groupName}" already existed (${group.id}); public link re-asserted`)
} else {
  const create = await asc.api("POST", "/v1/betaGroups", {
    data: {
      type: "betaGroups",
      attributes: {
        name: groupName,
        // Builds are attached explicitly by testflight-assign.ts, so the group must NOT auto-adopt
        // every build — that is what keeps a build going to internal testers only when we mean it.
        hasAccessToAllBuilds: false,
        ...attributes,
      },
      relationships: { app: { data: { type: "apps", id: appId } } },
    },
  })
  if (create.status !== 201) fail(`could not create beta group "${groupName}"`, create)
  group = create.body.data
  console.log(`created external beta group "${groupName}" (${group.id})`)
}

const link = group.attributes?.publicLink
console.log(
  link
    ? `public TestFlight link: ${link}`
    : "public link not issued yet — Apple publishes it once the group's first build clears Beta App Review",
)
