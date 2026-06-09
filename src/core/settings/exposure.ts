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
  const cloudflared = `# Quick tunnel (ephemeral URL):
cloudflared tunnel --url http://localhost:${port}

# Or a named tunnel mapping ${host} → localhost:${port} in your tunnel config:
#   ingress:
#     - hostname: ${host}
#       service: http://localhost:${port}`
  return { caddy, nginx, cloudflared }
}
