// Pure reverse-proxy config generators for the onboarding "expose publicly" step.
// nginx MUST carry WebSocket upgrade headers — the broker serves its live UI over WS.

export interface ExposureSnippets { caddy: string; nginx: string; cloudflared: string }

function hostOf(publicUrl: string): string {
  try { return new URL(publicUrl).hostname || "your-domain.example.com" }
  catch { return "your-domain.example.com" }
}

export function reverseProxySnippets(opts: { publicUrl: string; port: string }): ExposureSnippets {
  const host = hostOf(opts.publicUrl)
  const port = opts.port || "8787"
  const caddy = `${host} {
\treverse_proxy localhost:${port}
}`
  const nginx = `server {
    listen 443 ssl;
    server_name ${host};
    # ssl_certificate / ssl_certificate_key: your TLS certs

    location / {
        proxy_pass http://localhost:${port};
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}`
  const cloudflared = `# Named tunnel (stable) — maps ${host} → localhost:${port}:
#   ingress:
#     - hostname: ${host}
#       service: http://localhost:${port}
#
# Throwaway test only (URL changes every restart, you'll re-pair):
#   cloudflared tunnel --url http://localhost:${port}`
  return { caddy, nginx, cloudflared }
}

// ---------------------------------------------------------------------------
// Docker path: docker-compose.override.yml fragments that add a tunnel sidecar
// beside the broker. The broker service is named `broker`; in-network it is
// reachable as http://broker:<port>. Each fragment also overrides
// MUX_WEB_PUBLIC_URL on the broker (the base compose hardcodes it, so the
// override must win). Pure: provider + opts -> YAML string.
// ---------------------------------------------------------------------------

export type SidecarProvider = "cloudflared" | "tailscale" | "netbird" | "ngrok"

const OVERRIDE_HEADER =
  `# Save as docker-compose.override.yml next to your compose file,
# fill the <placeholders>, then run: docker compose up -d
`

export function composeSidecar(provider: SidecarProvider, opts: { port: string; host?: string }): string {
  const port = opts.port || "8787"
  const host = opts.host || ""
  const brokerUrl = host || "# set me — your public tunnel URL, e.g. https://your-domain.example.com"

  switch (provider) {
    case "cloudflared":
      return `${OVERRIDE_HEADER}services:
  broker:
    environment:
      # Must match the tunnel's public origin (override beats the base compose value).
      MUX_WEB_PUBLIC_URL: "${brokerUrl}"

  cloudflared:
    image: cloudflare/cloudflared:latest
    restart: unless-stopped
    # Quick tunnel (throwaway — URL changes every restart, you'll re-pair):
    command: tunnel --url http://broker:${port}
    # Named tunnel (stable) — paste the token from the Cloudflare dashboard instead:
    #   command: tunnel run --token <TOKEN>
    depends_on:
      - broker
`

    case "tailscale":
      return `${OVERRIDE_HEADER}services:
  broker:
    environment:
      MUX_WEB_PUBLIC_URL: "${brokerUrl}"

  tailscale:
    image: tailscale/tailscale:latest
    restart: unless-stopped
    hostname: supermux
    cap_add:
      - NET_ADMIN
    devices:
      - "/dev/net/tun"
    environment:
      TS_AUTHKEY: "<your-key>"
      TS_STATE_DIR: "/var/lib/tailscale"
    # After it joins your tailnet, expose the broker:
    #   tailscale serve --bg http://broker:${port}          # tailnet-only (mesh)
    #   tailscale funnel --bg http://broker:${port}         # public internet
    depends_on:
      - broker
`

    case "netbird":
      return `${OVERRIDE_HEADER}services:
  broker:
    environment:
      MUX_WEB_PUBLIC_URL: "${brokerUrl}"

  netbird:
    image: netbirdio/netbird:latest
    restart: unless-stopped
    cap_add:
      - NET_ADMIN
    devices:
      - "/dev/net/tun"
    environment:
      NB_SETUP_KEY: "<your-key>"
    # Reachable only by peers on your NetBird mesh; broker is http://broker:${port} in-network.
    depends_on:
      - broker
`

    case "ngrok":
      return `${OVERRIDE_HEADER}services:
  broker:
    environment:
      MUX_WEB_PUBLIC_URL: "${brokerUrl}"

  ngrok:
    image: ngrok/ngrok:latest
    restart: unless-stopped
    command: http broker:${port} --domain=${host || "<host>"}
    environment:
      NGROK_AUTHTOKEN: "<your-token>"
    depends_on:
      - broker
`
  }
}
