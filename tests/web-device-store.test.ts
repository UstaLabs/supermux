import { test, expect } from "bun:test"
import { unlinkSync, existsSync } from "fs"
import { DeviceStore } from "../src/channels/web/device-store"

const PATH = `/tmp/devices-test-${process.pid}.json`
function clean() { if (existsSync(PATH)) unlinkSync(PATH) }

test("mint creates a new device entry and returns the plaintext token", () => {
  clean()
  const ds = new DeviceStore(PATH)
  const { token, name } = ds.mint("iphone")
  expect(name).toBe("iphone")
  expect(token.length).toBeGreaterThan(40)
  const all = ds.list()
  expect(all.length).toBe(1)
  expect(all[0]!.name).toBe("iphone")
  clean()
})

test("verify accepts valid token and rejects others", () => {
  clean()
  const ds = new DeviceStore(PATH)
  const { token } = ds.mint("iphone")
  expect(ds.verify(token)?.name).toBe("iphone")
  expect(ds.verify("not-a-real-token")).toBe(undefined)
  clean()
})

test("verify hot-rereads when devices.json changes", () => {
  clean()
  const ds1 = new DeviceStore(PATH)
  ds1.mint("iphone")
  const ds2 = new DeviceStore(PATH)        // fresh instance, reads from disk
  expect(ds2.list()[0]!.name).toBe("iphone")
  clean()
})

test("revoke removes device", () => {
  clean()
  const ds = new DeviceStore(PATH)
  const { token } = ds.mint("iphone")
  ds.revoke("iphone")
  expect(ds.verify(token)).toBe(undefined)
  clean()
})

test("touch updates last_seen_at", async () => {
  clean()
  const ds = new DeviceStore(PATH)
  const { token } = ds.mint("iphone")
  ds.touch(token)
  const d = ds.list()[0]!
  expect(d.last_seen_at).toBeTruthy()
  clean()
})

test("verify sees a token minted by another instance after the cache is warm", () => {
  clean()
  const ds1 = new DeviceStore(PATH)
  const ds2 = new DeviceStore(PATH)
  ds1.mint("iphone")
  expect(ds2.list().length).toBe(1)        // ds2 cache now warm
  const { token } = ds1.mint("ipad")
  expect(ds2.verify(token)?.name).toBe("ipad")
  ds1.revoke("ipad")
  expect(ds2.verify(token)).toBe(undefined)
  clean()
})

test("touch writes at most once per interval", () => {
  clean()
  const ds = new DeviceStore(PATH)
  const { token } = ds.mint("iphone")
  ds.touch(token)
  const first = ds.list()[0]!.last_seen_at
  ds.touch(token)
  expect(ds.list()[0]!.last_seen_at).toBe(first)
  clean()
})

// Card #86: the CLI (`supermux revoke`) and a hand edit change devices.json from
// another process, so the broker's own revoke() never ran and the device's push
// rows outlived it. Whoever removes the device, the broker's listeners hear it.
test("a device removed by another process fires this instance's revoke listeners", () => {
  clean()
  const broker = new DeviceStore(PATH)
  broker.mint("fold")
  broker.mint("dev iphone")
  const heard: string[] = []
  broker.addRevokeListener((name) => heard.push(name))
  expect(broker.list().length).toBe(2)       // the broker's cache is warm

  new DeviceStore(PATH).revoke("dev iphone") // the CLI, in its own process
  broker.list()                              // the broker's next read sees the edit

  expect(heard).toEqual(["dev iphone"])
  clean()
})

test("an unreadable devices.json revokes nobody", () => {
  clean()
  const broker = new DeviceStore(PATH)
  broker.mint("fold")
  const heard: string[] = []
  broker.addRevokeListener((name) => heard.push(name))
  expect(broker.list().length).toBe(1)

  require("fs").writeFileSync(PATH, "{ half-written")
  broker.list()
  unlinkSync(PATH)
  broker.list()

  expect(heard).toEqual([])
  clean()
})

test("the broker's own revoke fires its listeners once", () => {
  clean()
  const broker = new DeviceStore(PATH)
  broker.mint("fold")
  const heard: string[] = []
  broker.addRevokeListener((name) => heard.push(name))

  broker.revoke("fold")
  broker.list()

  expect(heard).toEqual(["fold"])
  clean()
})
