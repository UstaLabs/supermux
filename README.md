<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="src/web-app/public/icons/icon-512.png">
    <img src="assets/logo/supermux.svg" alt="supermux logo" width="120">
  </picture>
</p>

# supermux

> **AFK. Still shipping.**
> supermux runs **Claude Code, Codex, Cursor & OpenCode** around the clock on a box you own — and puts every session on every screen you carry.

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](./LICENSE)
![Self-hosted](https://img.shields.io/badge/self--hosted-2ea44f)
![No vendor cloud](https://img.shields.io/badge/no%20vendor%20cloud-1f6feb)
![Built with supermux](https://img.shields.io/badge/built%20with%20supermux-blueviolet)

<!-- hero demo GIF (planned): push lands on phone → open web app → review diff → inline comment → agent fixes → merge -->

---

Coding agents only make progress while you sit in front of them. Close the laptop and the session dies; a finished diff waits hours for review; one question stalls a task until evening.

**supermux** gives your agents a box of their own — a VPS, a mini PC, the spare laptop in a drawer — where they run around the clock. The **web app** is your window into all of them: spawn a session from your phone, answer a worker's question from the train, review the diff after dinner, merge when it's right. **Telegram** is there too, when chat is all you need.

None of it touches a vendor cloud. supermux runs no servers and requires no account — your agent subscriptions, your code, your box. And we trust it with our own work: **supermux is built with supermux** ([see below](#built-with-supermux)).

## What changes for you

- **Leave whenever.** Sessions live on the box, not in your laptop — the lid closing changes nothing.
- **Never babysit.** Push notifications find you when a worker finishes or asks something; one tap opens that exact session.
- **Review from your phone.** Each session works in its own git worktree; you read the diff, leave inline comments, the agent addresses them, and finishing fast-forwards `main` only when you say so.
- **Delegate the fan-out.** An always-on personal-assistant session spawns, names, routes, and coordinates worker sessions — you describe the work, it runs the room.
- **Full autonomy, sane blast radius.** Agents don't stop to ask permission. They run on a dedicated box, in isolated worktrees, and nothing merges unreviewed.
- **It learns your stack.** A file-based shared memory plus a `soul.md` identity you define — knowledge compounds across sessions instead of resetting with each one.

## Quick start (Docker)

The fastest way to try it — no VPS required, no system changes:

```bash
git clone https://github.com/UstaLabs/supermux.git && cd supermux
docker compose up -d
# open http://localhost:8787 — the setup wizard launches automatically
```

The **setup wizard** runs on first open and walks you through five steps:

1. **Connect an agent** — paste a `CLAUDE_CODE_OAUTH_TOKEN` (get one via `claude setup-token`), an Anthropic or OpenAI API key, or use the Codex/Cursor device-flow ("authorize via link").
2. **Identity** — choose a name for your assistant and write its `soul.md`.
3. **Channels** — optionally add a Telegram bot token.
4. **Exposure** — optionally set a public URL; the wizard shows ready-to-paste Caddy, nginx, and Cloudflare Tunnel snippets and runs a reachability test.
5. **Done** — spawn your first session.

**First-run pairing is automatic:** the first browser to open a fresh instance is paired. On a headless box, `bun run pair <name>` does the same.

- Broker state (`~/.mux`) survives restarts via named volumes; `./workspace` is the directory your sessions edit — drop the repos you want worked on there.
- `http://localhost` is a browser **secure context**, so the installable PWA and push notifications work without HTTPS or a tunnel.
- **Prebuilt image:** uncomment `image: ghcr.io/ustalabs/supermux:latest` in `docker-compose.yml` to skip the local build.

> **Prefer guided setup — Docker *or* native?** Paste [`SETUP.md`](./SETUP.md) into Claude Code, Claude.ai, or ChatGPT and it walks you through it, step by step.

## A full workspace, in the browser

- **Code editor** — CodeMirror with a file tree, search, multi-tab editing, symbol navigation, and save-to-disk — plus a reload prompt when an agent edits the file you have open.
- **Git diff + review** — every uncommitted change since the session started, grouped by repo (nested repos included). Expand a file, leave an inline comment, send — the agent picks it up from there.
- **Terminal** — a genuine PTY (xterm.js) in the session's working directory; paste works, even on iOS.
- **Session launcher** — compose-first: pick the agent, the model, the thinking level, and the working directory, then go.
- **Usage dashboard** — rate limits and spend across Claude, Codex, and Cursor in one view.

## Four agents, one interface

**Claude Code, Codex, Cursor, and OpenCode** drive identically: same chat, same workspace, same orchestration. Switch models live mid-session, dial thinking effort up for the hard problems, and mix agents freely across sessions — one per project, one per task, however you slice it. If your subscription changes, your workflow doesn't.

## AFK. Still in the loop.

- **Web app** — the primary surface. An installable PWA with a separate chat per session, push notifications that know when you're already looking (and stay quiet), voice input with a live waveform, photos, files, camera.
- **Telegram** — available when chat is all you need: one chat multiplexed across every session, `/switch` between them, voice notes, photos, reactions, message edits.

## Yours

- **No vendor cloud.** supermux runs no servers and has no account system. Your phone reaches your box through a door you pick — LAN, your own reverse proxy, a tunnel, or your VPN — and the setup wizard configures it with you. (Telegram messages are as private as Telegram; the web app over your own domain or VPN is the fully-private path.)
- **Your credentials stay home.** Agent tokens, code, and session state live on your box and nowhere else.
- **Preview what they build.** The built-in reverse proxy gives any dev server a public HTTPS URL — device-paired by default, WebSocket/HMR included.

## Experimental

Moving fast, rough edges expected:

- **Android app** — a native client: chat, terminal, editor.
- **Screen streaming** — watch the host display, or an Android device via scrcpy, live in the chat.
- **Nightly knowledge curator** — an agent that tends the shared memory while you sleep.

## How it works

```
  phone · tablet · laptop               a box you own
 ┌─────────────────┐        ┌──────────────────────────────────────┐
 │  web app (PWA)  │        │  supermux broker                     │
 │  Telegram       │ ◀────▶ │  routing · push · files · proxy      │
 └─────────────────┘        │      │                               │
                            │   tmux sessions                      │
                            │ ┌──────┐ ┌─────┐ ┌──────┐ ┌────────┐ │
                            │ │claude│ │codex│ │cursor│ │opencode│ │
                            │ └──────┘ └─────┘ └──────┘ └────────┘ │
                            └──────────────────────────────────────┘
```

- **Broker** — one daemon that owns your channel credentials and supervises every session in tmux. Control commands (`/sessions`, `/spawn`, `/switch`, …) are handled by the broker itself, so routing burns no agent tokens.
- **Sessions** — each agent runs in its own tmux window; a small MCP shim proxies messages between broker and agent. Assistant sessions persist and orchestrate; workers are spawned per task and resume with full history after a restart.
- **Channels** — the web app and/or Telegram. Each session is its own thread; replies are pushed to your devices.

## Built with supermux

Every feature above was designed, implemented, and reviewed by agent sessions running inside supermux — the market research, the design specs, the code, the code review. This README itself was brainstormed, drafted, and shipped through a supermux session, steered from a tablet. We are the first users of every build.

## Requirements

- **Linux** (or the Docker image above on any host). An always-on box — a VPS, mini PC, or home server — is where it shines; your daily machine works too.
- **tmux** on `PATH`, plus at least one agent CLI logged in: `claude`, `codex`, `cursor-agent`, or `opencode`.
- A native install also needs [`bun`](https://bun.sh) and `git`. The broker runs fine **web-only** (no Telegram token) — set `MUX_WEB_PORT` + `MUX_WEB_PUBLIC_URL`.

## Configuration

Set these in `~/.mux/state/.env` (native) or `.env` (Docker):

| Variable | Purpose |
|---|---|
| `MUX_WEB_PORT`, `MUX_WEB_PUBLIC_URL` | Enable the web app (both required, or neither). |
| `MUX_TELEGRAM_BOT_TOKEN` | Enable Telegram (optional). |
| `MUX_PA_NAME` | Name of your personal-assistant session (default `assistant`). |
| `MUX_HOME`, `MUX_STATE_DIR` | Override where state lives (default `~/.mux` / `~/.mux/state`). |
| `MUX_PROXY_BASE_DOMAIN` | Enable the reverse proxy / public-URL feature (advanced). |

For a persistent native deployment there's a `systemd/mux.service` template; [`SETUP.md`](./SETUP.md) covers the whole native path.

## License

[MIT](./LICENSE) — free and open source.
