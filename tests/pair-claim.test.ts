import { test, expect, beforeEach, afterEach } from "bun:test"
import { existsSync, unlinkSync } from "fs"
import { mkdtempSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { WebChannel, __resetAuthFailures } from "../src/channels/web"
import { defaultAppConfig } from "../src/core/settings/app-config"

const PORT_A = 18794
const PORT_B = 18795
const DEV_PATH_A = `/tmp/devices-pair-claim-a-${process.pid}.json`
const DEV_PATH_B = `/tmp/devices-pair-claim-b-${process.pid}.json`

let chA: WebChannel
let chB: WebChannel

const baseOpts = (port: number, devicesFile: string, extra: object) => ({
  port,
  devicesFile,
  publicUrl: `http://127.0.0.1:${port}`,
  staticDir: undefined,
  getSessionsSnapshot: () => [],
  getSessionLog: () => [],
  setMute: () => {},
  onSendFromWeb: () => {},
  ...extra,
})

beforeEach(async () => {
  __resetAuthFailures()
  // Ensure fresh (empty) devices files
  if (existsSync(DEV_PATH_A)) unlinkSync(DEV_PATH_A)
  if (existsSync(DEV_PATH_B)) unlinkSync(DEV_PATH_B)

  // Channel A: empty store, onboarded: false — should allow one claim
  chA = new WebChannel(
    baseOpts(PORT_A, DEV_PATH_A, {
      getAppConfig: () => ({ ...defaultAppConfig, onboarded: false }),
    }),
  )
  await chA.start()

  // Channel B: empty store, onboarded: true — should reject claim
  chB = new WebChannel(
    baseOpts(PORT_B, DEV_PATH_B, {
      getAppConfig: () => ({ ...defaultAppConfig, onboarded: true }),
    }),
  )
  await chB.start()
})

afterEach(async () => {
  await chA.stop()
  await chB.stop()
  if (existsSync(DEV_PATH_A)) unlinkSync(DEV_PATH_A)
  if (existsSync(DEV_PATH_B)) unlinkSync(DEV_PATH_B)
})

const sameOriginHeaders = (port: number) => ({
  "content-type": "application/json",
  Origin: `http://127.0.0.1:${port}`,
})

test("fresh broker (empty store, onboarded:false): POST /pair/claim → 200 with paired:true and set-cookie", async () => {
  const res = await fetch(`http://127.0.0.1:${PORT_A}/pair/claim`, {
    method: "POST",
    headers: sameOriginHeaders(PORT_A),
    body: "{}",
  })
  expect(res.status).toBe(200)
  const body = await res.json() as any
  expect(body.paired).toBe(true)
  expect(typeof body.name).toBe("string")
  expect(body.name.length).toBeGreaterThan(0)
  const cookie = res.headers.get("set-cookie")
  expect(cookie).not.toBeNull()
  expect(cookie).toContain("cmux_token=")
})

test("second POST /pair/claim after first succeeds → 403 (device already exists)", async () => {
  // First claim — should succeed
  const first = await fetch(`http://127.0.0.1:${PORT_A}/pair/claim`, {
    method: "POST",
    headers: sameOriginHeaders(PORT_A),
    body: "{}",
  })
  expect(first.status).toBe(200)

  // Second claim — device exists now, must be rejected
  const second = await fetch(`http://127.0.0.1:${PORT_A}/pair/claim`, {
    method: "POST",
    headers: sameOriginHeaders(PORT_A),
    body: "{}",
  })
  expect(second.status).toBe(403)
})

test("already-onboarded broker: POST /pair/claim → 403", async () => {
  const res = await fetch(`http://127.0.0.1:${PORT_B}/pair/claim`, {
    method: "POST",
    headers: sameOriginHeaders(PORT_B),
    body: "{}",
  })
  expect(res.status).toBe(403)
})
