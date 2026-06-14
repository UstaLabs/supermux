// Format an exposed proxy's server-provided URL for display: drop the scheme and
// any trailing slash. The broker builds the canonical `url` (a subdomain when a
// wildcard base domain is configured, otherwise a /p/<slug>/ sub-path), so the
// client no longer reconstructs it from the page origin — this just prettifies it.
//   https://happy-otter.example.com    -> "happy-otter.example.com"
//   https://broker.example.com/p/app/  -> "broker.example.com/p/app"
export function displayUrl(url: string): string {
  return url.replace(/^https?:\/\//, "").replace(/\/+$/, "")
}
