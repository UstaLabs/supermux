# WhatsApp channel (via GOWA) — setup & pairing runbook

supermux talks to WhatsApp through a co-located **GOWA** sidecar
([go-whatsapp-web-multidevice](https://github.com/aldinokemal/go-whatsapp-web-multidevice),
a `whatsmeow` wrapper). GOWA owns the WhatsApp Web session for a **secondary**
phone number; supermux:

- sends **outbound** via GOWA's REST send endpoints (`/send/message`, `/send/image`, `/send/file`, `/send/audio`), and
- receives **inbound** via an HMAC-verified webhook the broker hosts on `127.0.0.1`.

Everything runs on `localhost`. GOWA and the broker live on the same box; GOWA is
never exposed publicly.

```
WhatsApp  ⇄  GOWA (127.0.0.1:3000)  ⇄  supermux broker
                 │  REST send  ◄───────────────  outbound
                 └─ webhook POST ──────────────►  127.0.0.1:3001 (HMAC-verified)
```

> **Capabilities (v1):** text + media (image / document) + inbound voice notes
> delivered as `kind:"voice"` attachments (Telegram parity). **DM-only.** No
> reactions, no edits, no TTS, no groups.

---

## 1. Install & run GOWA

GOWA ships as a single Go binary or a Docker image. Pick one. In both cases bind
the REST API to **loopback only** (`127.0.0.1:3000`) and protect it with basic
auth.

### Option A — single binary

```bash
# Download the latest release binary for your platform from
#   https://github.com/aldinokemal/go-whatsapp-web-multidevice/releases
# then run the REST server:
./whatsapp rest \
  --port=3000 \
  --basic-auth='gowauser:CHANGE_ME_STRONG_PASS' \
  --webhook='http://127.0.0.1:3001/webhook' \
  --webhook-secret='CHANGE_ME_WEBHOOK_SECRET'
```

GOWA reads media-handling from the environment. Enable eager auto-download so the
broker can fetch already-decrypted media off GOWA's static path (no extra
`/message/{id}/download` round-trip):

```bash
export WHATSAPP_AUTO_DOWNLOAD_MEDIA=true
```

(If you leave auto-download **off**, inbound media still works — the broker falls
back to GOWA's `GET /message/{id}/download` to decrypt+serve the file first — but
auto-download is simpler and faster.)

### Option B — Docker

```bash
docker run -d --name mux-gowa \
  --network host \
  -e WHATSAPP_AUTO_DOWNLOAD_MEDIA=true \
  -v "$HOME/.mux/gowa:/app/storages" \
  aldinokemal2104/go-whatsapp-web-multidevice \
  rest \
    --port=3000 \
    --basic-auth='gowauser:CHANGE_ME_STRONG_PASS' \
    --webhook='http://127.0.0.1:3001/webhook' \
    --webhook-secret='CHANGE_ME_WEBHOOK_SECRET'
```

`--network host` lets the container reach the broker's loopback webhook
(`127.0.0.1:3001`) and keeps GOWA's own `3000` on loopback. The `-v` mount
persists the paired session so you don't re-scan the QR on every restart.

> **Match the three knobs to the broker env (next section):**
> | GOWA flag | Broker env |
> |---|---|
> | `--port=3000` | `MUX_WHATSAPP_GOWA_URL=http://127.0.0.1:3000` |
> | `--basic-auth=gowauser:…` | `MUX_WHATSAPP_GOWA_BASIC_AUTH=gowauser:…` |
> | `--webhook=…:3001/webhook` | `MUX_WHATSAPP_WEBHOOK_PORT=3001` |
> | `--webhook-secret=…` | `MUX_WHATSAPP_WEBHOOK_SECRET=…` (must be identical) |
>
> The broker's webhook listener accepts a `POST` on **any** path of
> `127.0.0.1:<MUX_WHATSAPP_WEBHOOK_PORT>` (it verifies the `X-Hub-Signature-256`
> HMAC, not the path), so `/webhook` is a convention — any path GOWA posts to
> works as long as the port and secret match.

---

## 2. Broker environment variables

Set these wherever the broker reads its env (the systemd unit's
`Environment=`/`EnvironmentFile=`, or `~/.mux/state/.env`). Only
`MUX_WHATSAPP_GOWA_URL` is required to **enable** the channel; the rest configure it.

```bash
# Required — presence of this turns the WhatsApp channel ON:
MUX_WHATSAPP_GOWA_URL=http://127.0.0.1:3000

# GOWA REST basic auth ("user:pass") — must equal GOWA's --basic-auth (secret):
MUX_WHATSAPP_GOWA_BASIC_AUTH=gowauser:CHANGE_ME_STRONG_PASS

# Local webhook listener port — must equal the port in GOWA's --webhook URL.
# Defaults to 3001 if unset.
MUX_WHATSAPP_WEBHOOK_PORT=3001

# HMAC secret — must equal GOWA's --webhook-secret (secret):
MUX_WHATSAPP_WEBHOOK_SECRET=CHANGE_ME_WEBHOOK_SECRET

# Optional — GOWA v8 multi-device X-Device-Id header (omit unless you run
# multiple devices in one GOWA instance):
# MUX_WHATSAPP_GOWA_DEVICE_ID=
```

These can also be set from the onboarding/settings UI (they layer over env).
`MUX_WHATSAPP_GOWA_BASIC_AUTH` and `MUX_WHATSAPP_WEBHOOK_SECRET` are treated as
**secrets** and are redacted in config dumps.

Restart the broker after changing these. On boot you should see:

```
[INFO] [channels/whatsapp] whatsapp channel listening {"port":3001}
[INFO] [channels/whatsapp] whatsapp_ready {}
```

If the number isn't paired yet you'll instead see
`whatsapp_not_logged_in` (the channel still starts; just no messages flow until
you pair — see next section).

---

## 3. One-time pairing of the secondary number

Use a **dedicated secondary phone number** for the bot — pairing it as a WhatsApp
Web "linked device" does **not** log your main phone out, but you generally don't
want your personal number answering agent traffic.

GOWA's REST API is basic-auth protected, so include `-u user:pass` (or open the
GOWA web UI it serves on `:3000`).

**Either** scan a QR:

```bash
# Returns a QR (as an image/data URL); open it and scan from the secondary
# phone:  WhatsApp ▸ Settings ▸ Linked Devices ▸ Link a Device.
curl -u gowauser:CHANGE_ME_STRONG_PASS http://127.0.0.1:3000/app/login
```

**or** pair by code (no camera needed):

```bash
curl -u gowauser:CHANGE_ME_STRONG_PASS \
  'http://127.0.0.1:3000/app/login-with-code?phone=628123456789'
# Enter the returned 8-char code on the phone under Link a Device ▸ Link with phone number.
```

Confirm it's linked:

```bash
curl -u gowauser:CHANGE_ME_STRONG_PASS http://127.0.0.1:3000/app/status
# → { "results": { "is_connected": true, "is_logged_in": true } }
```

`is_logged_in: true` means GOWA holds a live session; the broker's `whatsapp_ready`
log confirms the same from its side.

---

## 4. Allowlist your number (deny-by-default)

The WhatsApp channel is **deny-by-default**: inbound DMs are dropped unless the
sender's number is on the allowlist. Add the number(s) allowed to talk to the bot
to the shared access file at **`~/.mux/state/access.json`** (the broker's
`ACCESS_FILE`; under `$MUX_STATE_DIR` if you override it):

```json
{
  "whatsapp": {
    "allowFrom": ["628123456789"]
  }
}
```

- Entries are **bare phone numbers** (country code + number, no `+`, no spaces).
- Matching is by the numeric local-part of the sender JID, so `628123456789`
  matches `628123456789@s.whatsapp.net` and device-suffixed JIDs like
  `628123456789:12@s.whatsapp.net`.
- An empty/missing list (or missing file) ⇒ **everything is denied**.
- The file is re-read per inbound message, so edits take effect without a restart.
  (Other channels keep their own sections in the same file; only the `whatsapp`
  key is read here.)

A dropped message is logged as `access_dropped_inbound {"from": "<jid>"}` — handy
when confirming your number is (or isn't) on the list.

---

## 5. Sample `systemd` unit for the GOWA sidecar

`/etc/systemd/system/mux-gowa.service` (binary install; adjust `User`, paths, and
the secrets):

```ini
[Unit]
Description=GOWA (WhatsApp) sidecar for supermux
After=network-online.target
Wants=network-online.target
# Keep GOWA's lifecycle tied to the broker if you run mux.service:
PartOf=mux.service

[Service]
Type=simple
User=mux
Group=mux
WorkingDirectory=/opt/gowa
# Persisted WhatsApp session lives here (survives restarts):
Environment=WHATSAPP_AUTO_DOWNLOAD_MEDIA=true
# Loopback-only REST + the webhook the broker listens on:
ExecStart=/opt/gowa/whatsapp rest \
  --port=3000 \
  --basic-auth=gowauser:CHANGE_ME_STRONG_PASS \
  --webhook=http://127.0.0.1:3001/webhook \
  --webhook-secret=CHANGE_ME_WEBHOOK_SECRET
Restart=on-failure
RestartSec=3
# Hardening (GOWA only needs loopback + its storage dir):
NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=strict
ReadWritePaths=/opt/gowa

[Install]
WantedBy=multi-user.target
```

Enable and start:

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now mux-gowa.service
sudo systemctl status mux-gowa.service
journalctl -u mux-gowa.service -f      # watch for the webhook + pairing
```

> Tip: start GOWA **before** (or alongside) the broker. If GOWA is down when the
> broker boots, the WhatsApp channel still starts and just logs
> `whatsapp_status_probe_failed`; it recovers once GOWA is reachable. Outbound
> sends while GOWA is down return a send error to the session (the agent sees the
> failure) rather than crashing the broker.

---

## 6. Smoke test (manual, needs a paired number)

With GOWA paired and the broker restarted with the WhatsApp env set, from an
**allow-listed** number DM the bot's WhatsApp number:

1. Send `hello` → a session is created/routed and the reply comes back on WhatsApp.
2. Send an **image with a caption** → reaches the session as a `photo` attachment.
3. Send a **PDF** → reaches the session as a `document` attachment.
4. Send a **voice note** → reaches the session as a `kind:"voice"` attachment.
5. Have the agent reply with **text** and with an **image file** → both arrive on WhatsApp.

If nothing routes: check `journalctl` for `access_dropped_inbound` (number not
allow-listed), `webhook_bad_signature` (secret mismatch between GOWA and the
broker), or `whatsapp_not_logged_in` (re-pair via `/app/login`).
