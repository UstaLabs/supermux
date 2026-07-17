import { expect, test } from "bun:test"
import { exposedLinksPublicUrl, hostRelayUrl } from "./public-url"

test("hostRelayUrl builds the host's connectivity relay URL", () => {
  expect(hostRelayUrl("habc", "relay.supermux.dev")).toBe("https://h-habc.relay.supermux.dev")
})

test("exposed links use the active relay URL when relay is enabled", () => {
  expect(exposedLinksPublicUrl({
    hostId: "habc",
    relayDomain: "relay.supermux.dev",
    relayUrl: "https://custom.relay.example",
    publicUrl: "http://localhost:8787",
  })).toBe("https://custom.relay.example")
})

test("exposed links use the deterministic relay URL while the relay connects", () => {
  expect(exposedLinksPublicUrl({
    hostId: "habc",
    relayDomain: "relay.supermux.dev",
    publicUrl: "http://localhost:8787",
  })).toBe("https://h-habc.relay.supermux.dev")
})

test("exposed links fall back to MUX_WEB_PUBLIC_URL when relay is disabled", () => {
  expect(exposedLinksPublicUrl({
    hostId: "habc",
    relayDomain: "",
    publicUrl: "https://broker.example.com",
  })).toBe("https://broker.example.com")
})
