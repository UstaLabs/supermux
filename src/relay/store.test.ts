import { expect, test } from "bun:test"
import { Database } from "bun:sqlite"
import { RelayStore } from "./store"

function freshStore(): RelayStore {
  return new RelayStore(new Database(":memory:"))
}

test("register mints a unique routing token and maps it to the push token", () => {
  const s = freshStore()
  const a = s.register("ios", "apns-tok-1")
  const b = s.register("android", "fcm-tok-2")
  expect(a).not.toEqual(b)
  expect(s.lookup(a)).toMatchObject({ platform: "ios", pushToken: "apns-tok-1" })
  expect(s.lookup(b)).toMatchObject({ platform: "android", pushToken: "fcm-tok-2" })
})

test("lookup of an unknown token is null; unregister removes it", () => {
  const s = freshStore()
  const t = s.register("ios", "tok")
  s.unregister(t)
  expect(s.lookup(t)).toBeNull()
  expect(s.lookup("never")).toBeNull()
})
