# Connectivity relay deployment

This directory is the production source of truth for the FRP-based connectivity relay at
`*.relay.supermux.dev`. It is separate from the encrypted APNs/FCM push relay in `src/relay/`.

The public edge is Caddy on ports 80/443. Host-side `frpc` connects to `frps` on port 7000.
The FRP HTTP vhost and Supermux control service listen only on loopback ports 8080 and 7200.
The control service verifies Ed25519 host proofs, mints 24-hour HMAC leases, validates FRP
Login/NewProxy hooks, and authorizes on-demand certificates only for verified hosts.

Required DNS: `*.relay.supermux.dev A <relay IPv4>`. The control endpoint is
`https://control.relay.supermux.dev`; it is covered by the same wildcard DNS record.

Build the control binary from the repository root:

```sh
bun build --compile --minify src/connectivity-relay/main.ts --outfile supermux-connectivity-relay
```

Production files:

- `/usr/local/bin/supermux-connectivity-relay`
- `/usr/local/bin/frps` (pinned to FRP 0.61.1)
- `/etc/supermux-relay/relay.env` (0600)
- `/etc/supermux-relay/frps.toml`
- `/etc/caddy/Caddyfile`
- `/var/lib/supermux-relay/hosts.json` (created after the first verified host)

Only ports 22, 80, 443, and 7000 should be reachable publicly.

Production deployment: OVH Ubuntu VPS at `162.19.137.12`, first deployed 2026-07-13. Caddy,
`frps`, and the control service are systemd-managed and enabled at boot; UFW, fail2ban, and
unattended security upgrades are enabled.

Quick health check:

```sh
curl --fail https://control.relay.supermux.dev/healthz
systemctl is-active supermux-connectivity-relay frps caddy fail2ban ufw unattended-upgrades
```

The host registry contains only identities that completed the Ed25519 proof. Temporary integration
test identities should be removed from `/var/lib/supermux-relay/hosts.json` after testing. Restart the
control service after editing it; `frps` deliberately uses a soft systemd dependency so a control-plane
restart does not take the data-plane listener down.
