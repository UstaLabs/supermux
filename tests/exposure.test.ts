import { test, expect } from "bun:test"
import { reverseProxySnippets } from "../src/core/settings/exposure"

test("snippets template host + port for all three proxies", () => {
  const s = reverseProxySnippets({ publicUrl: "https://mux.example.com", port: "8787" })
  expect(s.caddy).toContain("mux.example.com")
  expect(s.caddy).toContain("reverse_proxy localhost:8787")
  expect(s.nginx).toContain("server_name mux.example.com;")
  expect(s.nginx).toContain("proxy_pass http://localhost:8787;")
  expect(s.nginx).toContain("proxy_set_header Upgrade $http_upgrade;")
  expect(s.nginx).toContain('proxy_set_header Connection "upgrade";')
  expect(s.cloudflared).toContain("http://localhost:8787")
})
test("handles a URL with a path/port and derives the hostname", () => {
  const s = reverseProxySnippets({ publicUrl: "https://box.example.com:9443/", port: "8787" })
  expect(s.caddy).toContain("box.example.com")
  expect(s.nginx).not.toContain("https://")
})
test("falls back gracefully when publicUrl is empty", () => {
  const s = reverseProxySnippets({ publicUrl: "", port: "8787" })
  expect(s.caddy).toContain("your-domain.example.com")
})
