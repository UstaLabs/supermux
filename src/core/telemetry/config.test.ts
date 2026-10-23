import { describe, expect, test } from "bun:test"
import { resolveTelemetry, parseTelemetryBody } from "./config"

const release = { buildVersion: "0.14.0", testBroker: false }

describe("resolveTelemetry", () => {
  test("on by default in a release build", () => {
    expect(resolveTelemetry(undefined, {}, release)).toEqual({ enabled: true, source: "default", noticeShown: false })
  })
  test("DO_NOT_TRACK=1 wins over a saved 'on'", () => {
    expect(resolveTelemetry({ enabled: true }, { DO_NOT_TRACK: "1" }, release)).toMatchObject({ enabled: false, source: "env" })
  })
  test("MUX_TELEMETRY=0 turns it off; MUX_TELEMETRY=off too", () => {
    expect(resolveTelemetry(undefined, { MUX_TELEMETRY: "0" }, release).enabled).toBe(false)
    expect(resolveTelemetry(undefined, { MUX_TELEMETRY: "off" }, release).enabled).toBe(false)
  })
  test("saved choice beats the default", () => {
    expect(resolveTelemetry({ enabled: false }, {}, release)).toMatchObject({ enabled: false, source: "setting" })
  })
  test("dev builds and test brokers never send", () => {
    expect(resolveTelemetry(undefined, {}, { buildVersion: "dev", testBroker: false })).toMatchObject({ enabled: false, source: "build" })
    expect(resolveTelemetry(undefined, {}, { buildVersion: "0.14.0", testBroker: true })).toMatchObject({ enabled: false, source: "build" })
  })
  test("MUX_TELEMETRY_FORCE=1 overrides the build guard but not DO_NOT_TRACK", () => {
    const dev = { buildVersion: "dev", testBroker: true }
    expect(resolveTelemetry(undefined, { MUX_TELEMETRY_FORCE: "1" }, dev).enabled).toBe(true)
    expect(resolveTelemetry(undefined, { MUX_TELEMETRY_FORCE: "1", DO_NOT_TRACK: "1" }, dev).enabled).toBe(false)
  })
  test("noticeShown is carried through", () => {
    expect(resolveTelemetry({ noticeShown: true }, {}, release).noticeShown).toBe(true)
  })
})

describe("parseTelemetryBody", () => {
  test("accepts {enabled:boolean}", () => {
    expect(parseTelemetryBody({ enabled: false })).toEqual({ enabled: false })
  })
  test("rejects anything else", () => {
    expect(parseTelemetryBody({ enabled: "no" })).toEqual({ error: "enabled must be a boolean" })
    expect(parseTelemetryBody([])).toEqual({ error: "body must be an object" })
  })
})
