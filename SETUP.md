You are helping a human install and configure **supermux** on their machine. Work through the steps below conversationally: check prerequisites, ask the human the questions marked **ASK**, run or show the commands, and **VERIFY** after each step before moving on. Do not dump everything at once — one step, confirm it worked, then continue.

---

## What supermux is (context for you, the assistant)

supermux is a self-hosted broker that drives coding-agent sessions — Claude Code, Codex, and Cursor — from a phone or laptop over **Telegram** and a **web PWA**. The human runs many agent sessions in parallel; the broker routes chat messages to the right session and relays replies back with push notifications. Personal-assistant (PA) sessions are always-on orchestrators that can spawn and manage worker sessions.

It runs on Linux (or in a container on any host). It ships **no preset agent persona** — the human names their own assistant and writes their own `soul.md`. Nothing author-specific is baked in.

---

## Step 0 — Choose Docker vs Native (do this FIRST)

Before anything else, help the human pick one of two installation paths. Explain the tradeoffs, then **ASK** which they want, and branch to that path. Do not proceed until they choose.

- **Docker** — isolated, fast, no system changes, easy to throw away. The container bundles bun + tmux + git + the Claude Code CLI. Web PWA on `http://localhost:8787`; Telegram optional. A built-in **setup wizard** handles auth and configuration on first open. **Best for: "I just want to try it."**
- **Native** — the daily driver. Runs as a systemd **user** service so it survives reboots, supports full Telegram + an optional public URL / reverse proxy, and drives your real agents directly on the host. **Best for: "I want to actually use this."**

**ASK:** "Do you want the Docker path (quick taste-test) or the Native path (daily driver)?"

Then go to **Path A (Docker)** or **Path B (Native)** below.

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
2. **Setup wizard** (`/setup`) — the wizard launches automatically and walks through five steps:

   - **Step 1 — Connect an agent.** Paste a credential:
     - Claude Code: run `claude setup-token` on your host to get a `CLAUDE_CODE_OAUTH_TOKEN`, then paste it here.
     - Anthropic or OpenAI: paste an API key directly.
     - Codex / Cursor: choose "authorize via link" and complete the device-flow in your browser.
   - **Step 2 — Identity.** Choose a PA name and write your `soul.md` (voice, values, boundaries — the wizard seeds a starter template; make it yours).
   - **Step 3 — Channels.** Optionally add a Telegram bot token. Get one from **@BotFather** (`/newbot`) and paste it here — no `.env` editing required. The Telegram channel activates on the **next broker restart** after the token is saved.
   - **Step 4 — Exposure.** Optionally set a public URL. The wizard shows ready-to-paste config snippets for Caddy, nginx, and Cloudflare Tunnel, and runs a reachability test.
   - **Step 5 — Done.** Spawn your first session.

**VERIFY:** the wizard completes without errors and you can see the session list in the PWA.

### A4. (If Telegram was configured) Restart to activate
If you added a Telegram token in Step 3 of the wizard, restart the broker to bring the channel online:
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

### B1. Prerequisites
Verify each is on `PATH`:
```bash
uname -s        # expect Linux
bun --version
tmux -V
git --version
```
And **at least one** agent CLI:
```bash
claude --version    # or: codex --version   /   cursor-agent --version
```
**VERIFY:** Linux, plus `bun`, `tmux`, `git`, and at least one of `claude` / `codex` / `cursor-agent`. If an agent CLI is missing, install and log it in (`claude login`, `codex login`, or `cursor-agent login`) before continuing. The broker's startup preflight will also flag missing tools.

### B2. Choices
**ASK:**
- "Which channel(s) do you want — web, Telegram, or both?"
- If Telegram: "Get a bot token from **@BotFather** (`/newbot`) and paste it here."
- "Do you want a public URL / reverse proxy, or just local (LAN / localhost)?" (Public URL is optional and advanced — `MUX_WEB_PUBLIC_URL` must be the HTTPS address the browser actually uses; see `docs/web-channel-setup.md` and the proxy design spec.)
- "What's the **name** of your personal assistant (PA)?" (Default is `assistant`.)
- "Where should the PA's working directory be?" (Default: `~/.mux/workspace`.)

### B3. Get the code
```bash
git clone https://github.com/UstaLabs/supermux.git ~/projects/supermux
cd ~/projects/supermux
bun install
```
**VERIFY:** `bun install` completes; optionally `bun run typecheck` passes.

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

### B5. Identity
Do the **Identity** section below now, before first boot.

### B6. Run the broker
Foreground first, to confirm it boots cleanly:
```bash
cd ~/projects/supermux
bun src/main.ts
```
**VERIFY:** preflight passes (tmux + an agent CLI found, web env valid) and the broker reports the web channel listening. Stop it (Ctrl-C) once confirmed.

For a persistent install, use a **systemd user service**. Write `~/.config/systemd/user/mux.service`, templating the actual repo path:
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
**VERIFY:** `systemctl --user status supermux` shows `active (running)`; `journalctl --user -u supermux -n 40` shows a clean boot.

### B7. Connect a channel and test
- **Web:** pair a device — `bun run pair laptop` (run from the repo dir) prints a URL + QR; open it. **VERIFY:** the PWA loads and shows the session list.
- **Telegram:** message the bot. **VERIFY:** it responds (after pairing/allowlisting via the project's `/telegram:access` flow).

Then spawn a worker session and **VERIFY** it can edit a file in its working directory.

Go to the **"You're done when…"** checklist.

---

## Identity (Native path — important)

supermux ships **no preset agent persona**. Nothing named after the author or any specific identity is baked in. The human chooses their **own** personal-assistant name and writes their **own** `soul.md` (voice, values, boundaries) — shared by every session.

*(For the Docker path this is handled inside the setup wizard — see Path A, Step 2.)*

1. **Name the PA.** Default is `assistant`. To use a different name, set `MUX_PA_NAME` in `~/.mux/state/.env`.
2. **Write `soul.md`.** It lives at `~/.mux/soul.md`. On first boot supermux seeds a starter template there — guide the human to open it and make it theirs. Keep it short. A good `soul.md` covers:
   - **Identity** — one or two lines on who the assistant is to them.
   - **Communication style** — concise vs chatty, tone.
   - **Values** — what to optimize for (e.g. correctness over speed, action over discussion).
   - **Boundaries** — e.g. ask before destructive operations.

   Edit `~/.mux/soul.md` directly with any editor.

**VERIFY:** `soul.md` exists and reflects the human's own voice — confirm there is no author-specific name in it and that `MUX_PA_NAME` (if set) matches the name they want.

---

## Public URL / Reverse Proxy (both paths — advanced)

If the human wants the broker reachable from outside localhost, `MUX_WEB_PUBLIC_URL` must be the HTTPS address the browser actually uses. Three common options:

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

**Cloudflare Tunnel:**
```bash
cloudflared tunnel --url http://localhost:8787
```

The Docker setup wizard (Step 4) shows these same snippets and runs a reachability test automatically.

---

## You're done when…

- [ ] The chosen path's prerequisites all check out (Docker+compose, or Linux + bun + tmux + git + one agent CLI).
- [ ] The broker is running (Docker: `docker compose ps` shows `Up`; Native: `systemctl --user status supermux` is `active`).
- [ ] An agent credential is configured (Docker: via the wizard; Native: agent CLI is installed and logged in).
- [ ] At least one channel works: the web PWA loads at your `MUX_WEB_PUBLIC_URL`, and/or the Telegram bot responds.
- [ ] You named your own PA and wrote your own `soul.md` — no author-specific identity remains.
- [ ] You spawned a session and confirmed it can read and edit files in its working directory (`/workspace` for Docker).
