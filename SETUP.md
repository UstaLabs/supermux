You are helping a human install and configure **supermux** on their machine. Work through the steps below conversationally: check prerequisites, ask the human the questions marked **ASK**, run or show the commands, and **VERIFY** after each step before moving on. Do not dump everything at once — one step, confirm it worked, then continue.

---

## What supermux is (context for you, the assistant)

supermux is a self-hosted broker that drives coding-agent sessions — Claude Code, Codex, Cursor, and OpenCode — from a phone or laptop over **Telegram** and a **web PWA**. The human runs many agent sessions in parallel; the broker routes chat messages to the right session and relays replies back with push notifications. Personal-assistant (PA) sessions are always-on orchestrators that can spawn and manage worker sessions.

It runs on Linux (or in a container on any host). It ships **no preset agent persona** — the human names their own assistant and writes their own `soul.md`. Nothing author-specific is baked in.

---

## Step 0 — Where will supermux run? (do this FIRST)

Before anything else, help the human pick **where** supermux runs. Explain the tradeoffs, then **ASK**, and branch to that path. Do not proceed until they choose.

- **This computer (native)** — install directly on the machine you're on right now. Runs as a systemd **user** service so it survives reboots, supports full Telegram + an optional public URL / reverse proxy, and drives your real agents directly on the host **with full access**. **Best for: "this box is my always-on agent box."**
- **A mini PC / VPS over SSH (native)** — the *same* native install, but on a separate always-on box you reach by SSH (home server, mini PC, cloud VPS). You give the agent the SSH target and it connects and runs every step on that box for you — so the box gets **full host access** for your agents, exactly like a local native install. **Best for: "I have a dedicated box for this."**
- **Docker** — isolated container, no system changes, easy to throw away. Bundles bun + tmux + git + the Claude Code / Codex / Cursor CLIs; a built-in **setup wizard** handles auth and config on first open. **Best for: "I just want to try it."**

**ASK:** "Where should supermux run — this computer, a mini PC / VPS over SSH, or a throwaway Docker container?"

Routing:
- **Docker** → **Path A (Docker)**.
- **This computer** *or* **mini PC / VPS over SSH** → **Path B (Native)** — both run the same steps. For the SSH case, start at **B0 (Connect)**; for this computer, skip B0 and run the commands locally.

---

## Path A — Docker

### A1. Prerequisites
Verify both are installed:
```bash
docker --version
docker compose version
```
**VERIFY:** both print a version. If not, point the human at https://docs.docker.com/engine/install/ and stop here.

### A2. Get the code and start the broker
```bash
git clone https://github.com/UstaLabs/supermux.git && cd supermux
docker compose up -d
docker compose ps
```

> **Prebuilt image:** to skip the local build entirely, open `docker-compose.yml` and uncomment `image: ghcr.io/ustalabs/supermux:latest` — it is published automatically on version tags.

**VERIFY:** `docker compose ps` shows the `broker` service as `running`/`Up`. Check the logs are clean:
```bash
docker compose logs broker | tail -n 40
```
The preflight should report tmux + the `claude` CLI present and the web channel listening on 8787.

### A3. Open the app and run the setup wizard
Open `http://localhost:8787` in a browser. Because this is the first open on a fresh instance, **two things happen automatically:**

1. **Auto-pairing** — the first browser to reach a fresh instance is trusted and paired with no extra step. (On a headless or already-exposed box where there is no local browser, use `bun run pair <name>` instead to mint a pairing URL.)
2. **Setup wizard** (`/setup`) — launches automatically and walks through four steps:

   - **Welcome** — a quick intro to what you're about to set up.
   - **Agents** — connect a credential: paste a `CLAUDE_CODE_OAUTH_TOKEN` (run `claude setup-token` on your host), an Anthropic or OpenAI API key, or choose "authorize via link" for Codex / Cursor to complete the device-flow in your browser.
   - **Connectivity** — optionally add a Telegram bot token (from **@BotFather**, `/newbot` — no `.env` editing; the channel activates on the **next broker restart**), set a public URL (ready-to-paste Caddy / nginx / Cloudflare Tunnel snippets + a reachability test), and pair more devices.
   - **Done** — spawn your first session.

   *Identity isn't a wizard step* — it's automatic: once your assistant first spawns, the broker runs the `/mux:soul` skill and walks you through writing its `soul.md` (see **Identity** below).

**VERIFY:** the wizard completes without errors and you can see the session list in the PWA.

### A4. (If Telegram was configured) Restart to activate
If you added a Telegram token in the wizard's **Connectivity** step, restart the broker to bring the channel online:
```bash
docker compose restart broker
```
**VERIFY:** the logs show the Telegram channel started; messaging the bot gets a response.

### A5. Spawn and test a session
In the PWA, spawn a session pointed at `/workspace`. Drop a repo in the host `./workspace` directory first (or ask the agent to create a file).
**VERIFY:** the spawned session can list and edit files under `/workspace` — e.g. ask it to create `/workspace/hello.txt` and confirm the file appears in `./workspace` on the host.

Go to the **"You're done when…"** checklist.

---

## Path B — Native

This path serves **both** the "this computer" and the "mini PC / VPS over SSH" choices — the steps are identical. The only difference: for the SSH case you first connect (**B0**) and then run every command on the remote box; for this computer you skip B0 and run them locally. The handful of spots that differ for a remote box are flagged inline as **Remote (SSH path)** notes.

### B0. Connect (SSH path only — skip if installing on this computer)
You'll run the native install on the remote box over SSH. This assumes you can already reach it (an `ssh` key is set up).

**ASK:** "What's the SSH target — `user@host` (plus a non-default port or identity file, if any)?"

Verify the connection and that the box is Linux:
```bash
ssh <target> 'uname -s && whoami'
```
**VERIFY:** prints `Linux` and the expected user. From here on, run **every** command in B1–B6 **on the remote box** — wrap each one as `ssh <target> '<command>'`, or open one persistent SSH session and run them there. (`<target>` below always means this same `user@host`.)

### B1. Prerequisites + install the agent CLIs
Verify the base tools are on `PATH`:
```bash
uname -s        # expect Linux
bun --version
tmux -V
git --version
```
Then install **all four** coding-agent CLIs (the same set the Docker image bundles, plus OpenCode) so every agent is on `PATH` and ready the moment you have a subscription for it:
```bash
npm install -g @anthropic-ai/claude-code @openai/codex   # Claude Code + Codex
curl -fsS https://cursor.com/install | bash               # Cursor
npm install -g opencode-ai                                # OpenCode
```

Now **log into at least one** — whichever you actually have a subscription for. Installing the binary is free; **auth** is the real gate:
```bash
claude login          # and/or any of:
codex login
cursor-agent login
opencode auth login
```
**VERIFY:** `bun`, `tmux`, `git` are present, and at least one agent CLI is installed **and** logged in. The broker's startup preflight flags a missing `claude` / `codex` / `cursor-agent`; it does **not** check `opencode`, so if you'll use OpenCode confirm `opencode --version` yourself (a missing binary only surfaces when you spawn an OpenCode session).

> **Remote (SSH path):** a headless box has no browser, so `claude login` / `codex login` / `cursor-agent login` / `opencode auth login` print a URL (device / link flow) — open it in *your* laptop browser to finish, then re-check the CLI (`claude --version`, etc.) on the box.

### B2. Choices
**ASK:**
- "Which channel(s) do you want — web, Telegram, or both?"
- If Telegram: "Get a bot token from **@BotFather** (`/newbot`) and paste it here."
- "Do you want a public URL / reverse proxy, or just local (LAN / localhost)?" (Public URL is optional and advanced — `MUX_WEB_PUBLIC_URL` must be the HTTPS address the browser actually uses; see *Public URL / Reverse Proxy* below.)
- "What's the **name** of your personal assistant (PA)?" (Default is `assistant`.)
- "Where should the PA's working directory be?" (Default: `~/.mux/workspace`.)

### B3. Get the code
```bash
git clone https://github.com/UstaLabs/supermux.git ~/projects/supermux
cd ~/projects/supermux
bun install
# Build the web app (the PWA is served from a gitignored static dir —
# without this step the broker has no web UI):
cd src/web-app && bun install --frozen-lockfile && bun run build && cd ../..
```
**VERIFY:** `bun install` completes; `ls src/channels/web/static/index.html` exists (the web UI was built); optionally `bun run typecheck` passes.

### B4. Write the env file
State lives under `~/.mux/`. Create the env file at `~/.mux/state/.env`:
```bash
mkdir -p ~/.mux/state && chmod 700 ~/.mux
cat > ~/.mux/state/.env <<'EOF'
MUX_WEB_PORT=8787
MUX_WEB_PUBLIC_URL=http://localhost:8787
# MUX_TELEGRAM_BOT_TOKEN=123456:abc...   # uncomment + set if using Telegram
EOF
chmod 600 ~/.mux/state/.env
```
Adjust `MUX_WEB_PUBLIC_URL` if using a public URL / proxy (the HTTPS address the browser hits). The web channel turns on as soon as `MUX_WEB_PORT` + `MUX_WEB_PUBLIC_URL` are both set; no Telegram token is required.

> **Remote (SSH path):** `MUX_WEB_PUBLIC_URL` must be an address your phone/laptop can actually reach — the box's **LAN IP** (e.g. `http://192.168.1.50:8787`), a **VPN** address, or a **public URL** via reverse proxy / Cloudflare Tunnel (see *Public URL / Reverse Proxy* below). `http://localhost:8787` only works for the *this computer* path — on a remote box `localhost` is the box itself, not your browser. Whatever you set here is also what the pairing QR in **B6** points at, so make it reachable before you pair.

Relevant env vars (all optional, sensible defaults):

| Variable | Default | Purpose |
|---|---|---|
| `MUX_HOME` | `~/.mux` | Root of all supermux state + user content |
| `MUX_STATE_DIR` | `$MUX_HOME/state` | Broker DB, keys, `.env`, sockets |
| `MUX_TMUX_SESSION` | `supermux` | tmux session name the broker uses |
| `MUX_PA_NAME` | `assistant` | Name of the default personal assistant |
| `MUX_PA_WORKDIR` | `$MUX_HOME/workspace` | The PA's working directory |
| `MUX_TELEGRAM_BOT_TOKEN` | — | Set to enable the Telegram channel |

Set `MUX_PA_NAME` / `MUX_PA_WORKDIR` here too if the human chose non-defaults in B2 (these are environment variables the broker reads at boot — put them in the same `.env` or the systemd unit).

**VERIFY:** `cat ~/.mux/state/.env` shows the expected values; permissions are `600`.

### B5. Run the broker
Foreground first, to confirm it boots cleanly:
```bash
cd ~/projects/supermux
bun src/main.ts
```
**VERIFY:** preflight passes (tmux + an agent CLI found, web env valid) and the broker reports the web channel listening. Stop it (Ctrl-C) once confirmed.

For a persistent install, use a **systemd user service**. Write `~/.config/systemd/user/supermux.service`, templating the actual repo path:
```ini
[Unit]
Description=supermux broker
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
# Replace %h/projects/supermux with the real clone path if different:
WorkingDirectory=%h/projects/supermux
ExecStart=/usr/bin/env bun %h/projects/supermux/src/main.ts
Restart=on-failure
RestartSec=5
Environment=NODE_ENV=production

[Install]
WantedBy=default.target
```
Then:
```bash
systemctl --user daemon-reload
systemctl --user enable --now supermux
systemctl --user status supermux
```
(If you want the service to keep running after logout: `loginctl enable-linger $USER`.)

> **Remote (SSH path):** run `loginctl enable-linger $USER` now — it's **required** here. Without it, the systemd *user* service (and every session it runs) stops the moment your SSH session ends, so the box wouldn't stay up between connections.
**VERIFY:** `systemctl --user status supermux` shows `active (running)`; `journalctl --user -u supermux -n 40` shows a clean boot.

### B6. Connect a channel and test
- **Web:** pair a device — `bun run pair laptop` (run from the repo dir) prints a URL + QR; open it. **VERIFY:** the PWA loads and shows the session list.
- **Telegram:** message the bot. **VERIFY:** it responds (after pairing/allowlisting via the project's `/telegram:access` flow).

Then spawn a worker session and **VERIFY** it can edit a file in its working directory.

Go to the **"You're done when…"** checklist.

---

## Identity — automatic (both paths)

supermux ships **no preset agent persona** — and you don't set it up by hand. The first time your default assistant session spawns, the broker auto-runs the **`/mux:soul`** skill, which walks you through writing its `~/.mux/soul.md` (voice, values, boundaries — in your own words). A starter `soul.md` is seeded on first boot, so the box works immediately; the skill makes it yours.

- Want a specific **name** from the first boot? Set `MUX_PA_NAME` in `~/.mux/state/.env` (default `assistant`).
- **Revise anytime** — re-run `/mux:soul`, edit `~/.mux/soul.md` directly, or use **Assistant Settings** in the web app.

---

## Public URL / Reverse Proxy (any path — advanced)

If the human wants the broker reachable from outside localhost, `MUX_WEB_PUBLIC_URL` must be the HTTPS address the browser actually uses. Three common options:

### Exposing apps: subdomains vs. sub-paths

`expose_port` publishes a session's local port to the web. There are two modes,
chosen automatically by configuration:

- **Subdomain mode** — set `MUX_PROXY_BASE_DOMAIN` (e.g. `apps.example.com`).
  Each app gets `https://<slug>.apps.example.com`. Requires wildcard DNS
  (`*.apps.example.com`) and a wildcard TLS cert (e.g. via Cloudflare). Works for
  any app — each gets its own origin.
- **Path mode** — leave `MUX_PROXY_BASE_DOMAIN` unset. Apps are served under your
  existing broker URL at `https://<broker>/p/<slug>/`. No wildcard DNS or TLS
  required — ideal when you reach the broker over a single hostname (Tailscale, a
  quick tunnel, or one cert).

  Path mode strips the `/p/<slug>` prefix before forwarding, so the app is
  presented as if at the site root. It works for apps that use **relative** URLs.
  Apps that assume they live at the domain root — absolute `/asset` links,
  `fetch('/api')`, a hardcoded root WebSocket, or a framework dev server that
  hardcodes its own base path (Vite, Next.js) — won't work under a sub-path. Use
  **subdomain mode** for those.

**Caddy** (automatic TLS):
```
reverse_proxy /api/* localhost:8787
reverse_proxy /* localhost:8787
```

**nginx:**
```nginx
location / {
    proxy_pass http://localhost:8787;
    proxy_http_version 1.1;
    proxy_set_header Upgrade $http_upgrade;
    proxy_set_header Connection "upgrade";
    proxy_set_header Host $host;
}
```

**Cloudflare Tunnel** — two flavors:

*Quick tunnel (ephemeral — test only):*
```bash
cloudflared tunnel --url http://localhost:8787
```
Hands back a random `https://<random>.trycloudflare.com` URL with zero config — fine for a one-off test, but it **changes every time `cloudflared` restarts**, and a new URL means re-pairing every device. Don't use it for a box you intend to keep.

*Named tunnel (stable — daily driver):* a fixed `https://mux.yourdomain.com` that survives restarts. Requires a domain you've added to Cloudflare.
```bash
# 1. install cloudflared (Debian/Ubuntu)
curl -L -o cloudflared.deb https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-amd64.deb
sudo dpkg -i cloudflared.deb

# 2. authenticate, create the tunnel, route a hostname to it
cloudflared tunnel login                          # opens a browser; pick your domain
cloudflared tunnel create supermux
cloudflared tunnel route dns supermux mux.yourdomain.com
```
Save `~/.cloudflared/config.yml` (use the UUID printed by `tunnel create`):
```yaml
tunnel: <UUID>
credentials-file: /home/<you>/.cloudflared/<UUID>.json
ingress:
  - hostname: mux.yourdomain.com
    service: http://localhost:8787
  - service: http_status:404
```
Run it as a service, then point supermux at the public URL and restart:
```bash
sudo cloudflared service install
sudo systemctl enable --now cloudflared
# then set in ~/.mux/state/.env:  MUX_WEB_PUBLIC_URL=https://mux.yourdomain.com
# and restart the broker so it picks up the new URL
```
(On the SSH path, run these on the remote box too — `cloudflared tunnel login` on a headless box prints a URL to open in your laptop browser.)

The Docker setup wizard (Step 4) shows these same snippets and runs a reachability test automatically.

---

## You're done when…

- [ ] The chosen path's prerequisites all check out (Docker+compose, or Linux + bun + tmux + git + one agent CLI).
- [ ] The broker is running (Docker: `docker compose ps` shows `Up`; Native: `systemctl --user status supermux` is `active`).
- [ ] An agent credential is configured (Docker: via the wizard; Native: agent CLI is installed and logged in).
- [ ] At least one channel works: the web PWA loads at your `MUX_WEB_PUBLIC_URL`, and/or the Telegram bot responds.
- [ ] Your assistant set up its `soul.md` — the automatic `/mux:soul` flow ran on first spawn (revise it anytime).
- [ ] You spawned a session and confirmed it can read and edit files in its working directory (`/workspace` for Docker).
