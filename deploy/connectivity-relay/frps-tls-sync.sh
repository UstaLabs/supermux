#!/bin/sh
# Copies Caddy's Let's Encrypt certificate for control.relay.supermux.dev (the name frpc and the
# in-process client dial on :7000) to where frps can read it, and restarts frps when it changed.
# frps reads its certificate only at start; Caddy renews it about every 60 days.
# Installed as /usr/local/sbin/supermux-frps-tls-sync, run daily by supermux-frps-tls-sync.timer.
set -eu
NAME=control.relay.supermux.dev
SRC=/var/lib/caddy/.local/share/caddy/certificates/acme-v02.api.letsencrypt.org-directory/$NAME
DST=/etc/supermux-relay/tls
[ -s "$SRC/$NAME.crt" ] && [ -s "$SRC/$NAME.key" ] || { echo "no certificate for $NAME from Caddy yet" >&2; exit 1; }
# A pair that does not match would stop frps from starting.
[ "$(openssl x509 -in "$SRC/$NAME.crt" -noout -pubkey)" = "$(openssl pkey -in "$SRC/$NAME.key" -pubout)" ] || { echo "certificate and key do not match" >&2; exit 1; }
install -d -o root -g supermux-relay -m 0750 "$DST"
if cmp -s "$SRC/$NAME.crt" "$DST/frps.crt" && cmp -s "$SRC/$NAME.key" "$DST/frps.key"; then exit 0; fi
install -o root -g supermux-relay -m 0640 "$SRC/$NAME.key" "$DST/frps.key.new"
install -o root -g supermux-relay -m 0644 "$SRC/$NAME.crt" "$DST/frps.crt.new"
mv "$DST/frps.key.new" "$DST/frps.key"
mv "$DST/frps.crt.new" "$DST/frps.crt"
echo "frps certificate updated: $(openssl x509 -in "$DST/frps.crt" -noout -enddate)"
# Only an frps configured with this certificate needs the restart (every tunnel reconnects in ~5 s).
if grep -q '^transport.tls.certFile' /etc/supermux-relay/frps.toml; then systemctl restart frps; echo "frps restarted"; fi
