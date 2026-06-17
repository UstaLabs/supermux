import { test, expect } from "bun:test"
import { reverseProxySnippets, composeSidecar } from "./exposure"

test("cloudflared snippet leads with the stable/named tunnel, demotes the quick one", () => {
  const s = reverseProxySnippets({ publicUrl: "https://mux.example.com", port: "8787" })
  const namedIdx = s.cloudflared.indexOf("Named tunnel (stable)")
  const quickIdx = s.cloudflared.indexOf("Throwaway test only")
  expect(namedIdx).toBeGreaterThanOrEqual(0)
  expect(quickIdx).toBeGreaterThanOrEqual(0)
  // stable/named text must come BEFORE the quick/throwaway text
  expect(namedIdx).toBeLessThan(quickIdx)
  // still templates host + port
  expect(s.cloudflared).toContain("mux.example.com")
  expect(s.cloudflared).toContain("http://localhost:8787")
})

test("composeSidecar cloudflared wires the broker + override", () => {
  const y = composeSidecar("cloudflared", { port: "8787" })
  expect(y).toContain("cloudflare/cloudflared")
  expect(y).toContain("broker:8787")
  expect(y).toContain("MUX_WEB_PUBLIC_URL")
  // named-tunnel token form is mentioned as a comment
  expect(y).toContain("tunnel run --token")
  // valid-shaped fragment under services:
  expect(y).toContain("services:")
})

test("composeSidecar tailscale adds the tun caps/devices", () => {
  const y = composeSidecar("tailscale", { port: "8787" })
  expect(y).toContain("NET_ADMIN")
  expect(y).toContain("/dev/net/tun")
  expect(y).toContain("tailscale/tailscale")
  expect(y).toContain("MUX_WEB_PUBLIC_URL")
})

test("composeSidecar netbird adds the tun caps/devices + setup key", () => {
  const y = composeSidecar("netbird", { port: "8787" })
  expect(y).toContain("NET_ADMIN")
  expect(y).toContain("/dev/net/tun")
  expect(y).toContain("netbirdio/netbird")
  expect(y).toContain("NB_SETUP_KEY")
})

test("composeSidecar ngrok templates host + authtoken", () => {
  const y = composeSidecar("ngrok", { port: "8787", host: "mux.example.com" })
  expect(y).toContain("ngrok/ngrok")
  expect(y).toContain("http broker:8787 --domain=mux.example.com")
  expect(y).toContain("NGROK_AUTHTOKEN")
})

test("composeSidecar uses host for MUX_WEB_PUBLIC_URL when provided, placeholder otherwise", () => {
  const withHost = composeSidecar("cloudflared", { port: "8787", host: "https://mux.example.com" })
  expect(withHost).toContain('MUX_WEB_PUBLIC_URL: "https://mux.example.com"')
  const noHost = composeSidecar("cloudflared", { port: "8787" })
  expect(noHost).toContain("set me")
})
