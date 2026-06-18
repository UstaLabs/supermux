<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="src/web-app/public/icons/icon-512.png">
    <img src="assets/logo/supermux.svg" alt="supermux logo" width="120">
  </picture>
</p>

<h1 align="center">supermux</h1>

<p align="center"><strong>AFK. Still shipping.</strong></p>

<p align="center">
  supermux runs <strong>Claude Code, Codex, Cursor &amp; OpenCode</strong> 24/7 on a box you own —<br>
  every session, on every screen you carry.
</p>

<p align="center">
  <a href="./LICENSE"><img src="https://img.shields.io/badge/License-MIT-yellow.svg" alt="License: MIT"></a>
  <img src="https://img.shields.io/badge/self--hosted-2ea44f" alt="Self-hosted">
  <img src="https://img.shields.io/badge/no%20vendor%20cloud-1f6feb" alt="No vendor cloud">
  <img src="https://img.shields.io/badge/built%20with%20supermux-blueviolet" alt="Built with supermux">
</p>

<p align="center">
  <a href="https://supermux.dev"><strong>Website</strong></a> ·
  <a href="#install"><strong>Install</strong></a> ·
  <a href="./SETUP.md"><strong>Setup guide</strong></a>
</p>

---

## Agents only work while you watch

Coding agents only make progress while you sit in front of them. Close the laptop and the session dies; a finished diff waits hours for review; one question stalls a task until evening.

**supermux** gives your agents a box of their own — a VPS, a mini PC, the spare laptop in a drawer — where they run around the clock. The web app (and Telegram) is your window into all of them: spawn a session from your phone, answer a worker's question from the train, review the diff after dinner, merge when it's right.

No vendor cloud, no account. Your agent subscriptions, your code, your box.

```sh
curl -fsSL https://supermux.dev/install.sh | sh
```

<sub>Windows (PowerShell): <code>irm https://supermux.dev/install.ps1 | iex</code> — more options under <a href="#install">Install</a>.</sub>

## What changes for you

- **Leave whenever.** Sessions live on the box, not your laptop — the lid closing changes nothing.
- **Never babysit.** A push finds you when a worker finishes or asks; one tap opens that exact session.
- **Review from your phone.** Each session works in its own git worktree — read the diff, comment inline, the agent fixes, and `main` moves only on your word.
- **Autonomous, not reckless.** No permission prompts; isolated worktrees, a dedicated box, and nothing merges unreviewed.
- **It learns your stack.** Shared memory across sessions plus a `soul.md` identity you write — knowledge compounds instead of resetting.

## Your window into every session

- **A chat per session.** An installable PWA with one thread per session — voice input with a live waveform, photos, camera, and push that stays quiet while you're already looking. Telegram works too, when chat is all you need.
- **Code review, pocket-sized.** Read the full diff grouped by repo, drop inline comments, the agent addresses them — `main` fast-forwards only when you say so.
- **A full workspace in the browser.** File tree, editor with search, and a real terminal (xterm.js PTY) in the session's directory. Rate limits and spend across every agent in one dashboard.

## Four agents, one interface

**Claude Code, Codex, Cursor, and OpenCode** drive identically — same chat, same workspace, same orchestration. Switch models live mid-session, dial thinking effort up for the hard problems, and mix agents freely across sessions. If your subscription changes, your workflow doesn't.

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

- **One daemon, your keys.** The broker owns your channel credentials and supervises every session in tmux. Control commands (`/spawn`, `/switch`, …) run in the broker itself, so routing burns no agent tokens.
- **Sessions that survive.** Restart the broker or reboot the box — every session resumes with full history. Assistant sessions persist and orchestrate; workers are spawned per task.
- **Yours, end to end.** No vendor cloud, no account. Reach your box over LAN, your own reverse proxy, a tunnel, or a VPN — your call.

## Install

One command, and it walks you through the rest.

**Mac / Linux**

```sh
curl -fsSL https://supermux.dev/install.sh | sh
```

**Windows** (PowerShell)

```powershell
irm https://supermux.dev/install.ps1 | iex
```

The installer offers Docker or a native install, configures connectivity, and pairs your first device. Prefer to do it by hand? Either of these also works:

- **Docker** — `git clone https://github.com/UstaLabs/supermux.git && cd supermux && docker compose up -d`, then open `http://localhost:8787`. State persists in named volumes; drop the repos you want worked on into `./workspace`. Uncomment `image: ghcr.io/ustalabs/supermux:latest` to skip the local build.
- **Guided** — paste [`SETUP.md`](./SETUP.md) into Claude Code, Claude.ai, or ChatGPT; it asks *where* to run supermux (this computer, a mini PC / VPS over SSH, or a throwaway container) and walks you through it.

On first open, a **setup wizard** covers agents (paste a `CLAUDE_CODE_OAUTH_TOKEN` from `claude setup-token`, an Anthropic/OpenAI API key, or a device-flow login), connectivity (optional Telegram token, a public URL with ready-to-paste Caddy / nginx / Cloudflare Tunnel snippets), and your first session. The first browser to open a fresh instance is paired automatically; on a headless box, `bun run pair <name>` does the same.

## Configuration

Set these in `~/.mux/state/.env` (native) or `.env` (Docker):

| Variable | Purpose |
|---|---|
| `MUX_WEB_PORT`, `MUX_WEB_PUBLIC_URL` | Enable the web app (both required, or neither). |
| `MUX_TELEGRAM_BOT_TOKEN` | Enable Telegram (optional). |
| `MUX_PA_NAME` | Name of your personal-assistant session (default `assistant`). |
| `MUX_HOME`, `MUX_STATE_DIR` | Override where state lives (default `~/.mux` / `~/.mux/state`). |
| `MUX_PROXY_BASE_DOMAIN` | Enable the reverse proxy / public-URL feature (advanced). |

## Requirements

- **macOS (Apple Silicon) or Linux** natively — or the **Docker** image on any host. Windows runs it through WSL2 (the PowerShell installer sets that up). An always-on box — VPS, mini PC, or home server — is where it shines; your daily machine works too.
- **tmux** on `PATH`, plus at least one agent CLI logged in: `claude`, `codex`, `cursor-agent`, or `opencode`.
- Native installs also need [`bun`](https://bun.sh) and `git`. The broker runs fine web-only (no Telegram token) — just set `MUX_WEB_PORT` + `MUX_WEB_PUBLIC_URL`.

## Built with supermux

Every feature above was designed, implemented, and reviewed by agent sessions running inside supermux — the research, the specs, the code, the review. This README included, steered from a tablet. We're the first users of every build.

## License

[MIT](./LICENSE) — free and open source.
