// Build the public URL for an exposed proxy from the current page origin.
//
// A proxy's `domain` is just the subdomain label (e.g. "happy-otter"); the
// reachable host is that label prefixed onto the page's base domain — the last
// two labels of the current hostname. This mirrors the logic the Proxies page
// has always used, so links open to the same place from anywhere in the app.

/** Extract the base domain (last two labels) from a hostname. */
export function baseDomainOf(host: string): string {
  const parts = host.split(".")
  return parts.length >= 2 ? parts.slice(-2).join(".") : host
}

/** Full reachable host for a proxy, e.g. "happy-otter.example.com". */
export function proxyHostname(domain: string): string {
  return `${domain}.${baseDomainOf(window.location.hostname)}`
}

/** Full URL for a proxy, e.g. "https://happy-otter.example.com". */
export function proxyUrl(domain: string): string {
  return `${window.location.protocol}//${proxyHostname(domain)}`
}
