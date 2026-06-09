import { test, expect } from "bun:test"
import { parseAdbDevices } from "../src/core/display/scrcpy/adb"

test("parses adb devices output", () => {
  const out = `List of devices attached
emulator-5554\tdevice
0A081FDD40012345\tdevice
192.168.1.5:5555\toffline
`
  const d = parseAdbDevices(out)
  expect(d).toEqual([
    { serial: "emulator-5554", state: "device" },
    { serial: "0A081FDD40012345", state: "device" },
    { serial: "192.168.1.5:5555", state: "offline" },
  ])
})

test("empty list", () => {
  expect(parseAdbDevices("List of devices attached\n\n")).toEqual([])
})
