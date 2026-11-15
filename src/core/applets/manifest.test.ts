import { expect, test } from "bun:test"
import { isAppletName, isOwnerOnly, parseEvery, parseManifest } from "./manifest"

test("the smallest applet is a title", () => {
  const r = parseManifest(`{"title":"Notes"}`)
  expect(r).toEqual({ ok: true, manifest: { title: "Notes", jobs: [], secrets: [], bin: [], config: [], ownerOnly: [] } })
})

test("the definition page's job tracker parses whole", () => {
  const r = parseManifest(JSON.stringify({
    title: "Job tracker", icon: "public/icon.svg", run: "python3 server.py", bin: ["jobs"],
    jobs: [{ name: "gmail", every: "30m", run: "python3 jobs/gmail.py" }], secrets: ["GMAIL_OAUTH_TOKEN"],
    egress: ["gmail.googleapis.com"],
    config: [{ key: "pollEvery", label: "Check Gmail every", type: "select", options: ["15m", "30m", "1h"], default: "30m" }],
    ownerOnly: ["/api/settings"],
  }))
  if (!r.ok) throw new Error(r.error)
  expect(r.manifest.run).toBe("python3 server.py")
  expect(r.manifest.jobs).toEqual([{ name: "gmail", every: "30m", run: "python3 jobs/gmail.py" }])
  expect(r.manifest.config[0]!.options).toEqual(["15m", "30m", "1h"])
})

test("a known key with the wrong shape is an error the agent can read, never a silent static applet", () => {
  expect(parseManifest(`{"title":"x","run":5}`)).toEqual({ ok: false, error: `"run" must be a non-empty string` })
  expect(parseManifest(`{"run":"bun s.ts"}`)).toEqual({ ok: false, error: `"title" is required` })
  expect(parseManifest(`{"title":"x","jobs":[{"every":"5s","run":"a"}]}`).ok).toBe(false)
  expect(parseManifest(`{"title":"x","secrets":["gmail token"]}`).ok).toBe(false)
  expect(parseManifest(`{"title":"x","icon":"../../etc/passwd"}`).ok).toBe(false)
  expect(parseManifest(`{"title":"x","icon":"/etc/passwd"}`).ok).toBe(false)
  expect(parseManifest(`{"title":"x","ownerOnly":["api"]}`).ok).toBe(false)
  expect(parseManifest(`not json`).ok).toBe(false)
  expect(parseManifest(`[]`).ok).toBe(false)
})

test("jobs get a name when they have none, and two with one name are refused", () => {
  const r = parseManifest(`{"title":"x","jobs":[{"every":"1h","run":"a"},{"every":"1d","run":"b"}]}`)
  expect(r.ok && r.manifest.jobs.map((j) => j.name)).toEqual(["job1", "job2"])
  expect(parseManifest(`{"title":"x","jobs":[{"name":"a","every":"1h","run":"a"},{"name":"a","every":"1d","run":"b"}]}`).ok).toBe(false)
})

test("every", () => {
  expect(parseEvery("30m")).toBe(1_800_000)
  expect(parseEvery("1h")).toBe(3_600_000)
  expect(parseEvery("90s")).toBe(90_000)
  expect(parseEvery("30s")).toBeNull()
  expect(parseEvery("often")).toBeNull()
})

test("names are route segments", () => {
  for (const n of ["notes", "job-tracker", "a1"]) expect(isAppletName(n)).toBe(true)
  for (const n of ["", "Notes", "1st", "-x", "x-", "a/b", "a.b", ".git", "x".repeat(49)]) expect(isAppletName(n)).toBe(false)
})

test("ownerOnly matches a path and what is under it, not a longer name", () => {
  expect(isOwnerOnly("/api/settings", ["/api/settings"])).toBe(true)
  expect(isOwnerOnly("/api/settings/x?y=1", ["/api/settings"])).toBe(true)
  expect(isOwnerOnly("/api/settings?y=1", ["/api/settings/"])).toBe(true)
  expect(isOwnerOnly("/api/settingsx", ["/api/settings"])).toBe(false)
  expect(isOwnerOnly("/", ["/api"])).toBe(false)
})

test("ownerOnly can't be stepped around by encoding, slashes, case or params", () => {
  const o = ["/api/settings"]
  for (const p of ["/api/%73ettings", "/api/%2573ettings", "//api//settings", "/API/Settings", "/api/settings;x=1", "/api/settings.", "/api\\settings", "/api/settings%2Fx"]) {
    expect([p, isOwnerOnly(p, o)]).toEqual([p, true])
  }
  expect(isOwnerOnly("/api/settingsx", o)).toBe(false)
})
