# supermux

> Your coding agents, in your pocket. Drive **Claude Code, Codex, and Cursor** from your phone or laptop — over Telegram and a web app — across as many parallel sessions as you want.

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](./LICENSE)
&nbsp;Free · open source · self-hosted · no cloud relay

---

You kick off an agent at your desk, then leave. Ten minutes later it's blocked on a permission prompt, or you think of another task you'd love to start — and you're stuck waiting until you're back at the keyboard.

**supermux** is a self-hosted broker that puts your coding agents on the channels you already carry. Talk to an always-on **personal-assistant** session that spawns and coordinates **worker** sessions for you; approve a prompt from Telegram on the train; open the **web app** to read a diff, edit a file, or pop a terminal when you actually need to dig in. Everything runs on your own machine — your agent auth, your bot token, your code. Nothing is relayed through anyone else's servers.

**What it's not:** not a hosted service (there's no cloud middleman), not a single-session Telegram bridge (it runs many agents in parallel and orchestrates them), and not locked to one agent (Claude Code, Codex, and Cursor share the same interface).

## Quick start (Docker)

The fastest way to try it on your own machine — no VPS, no system changes:

```bash
git clone https://github.com/UstaLabs/supermux.git && cd supermux
docker compose up -d
# open http://localhost:8787 — the setup wizard launches automatically
```

The **setup wizard** (`/setup`) runs on first open and walks you through five steps:

1. **Connect an agent** — paste a `CLAUDE_CODE_OAUTH_TOKEN` (get one via `claude setup-token`), an Anthropic or OpenAI API key, or use the Codex/Cursor device-flow ("authorize via link").
2. **Identity** — choose a PA name and write your `soul.md`.
3. **Channels** — optionally add a Telegram bot token (paste, not file editing; activates on the next broker restart).
4. **Exposure** — optionally set a public URL; the wizard shows ready-to-paste Caddy, nginx, and Cloudflare Tunnel snippets and runs a reachability test.
5. **Done** — spawn your first session.

**First-run pairing is automatic:** the first browser to open a fresh instance is auto-paired. On a headless or already-exposed box, use `bun run pair <name>` instead (still works).

- Broker state (`~/.mux`) survives restarts via named volumes; `./workspace` is the directory your sessions edit — drop the repos you want worked on there.
- `http://localhost` is a browser **secure context**, so the installable PWA and push notifications work without HTTPS or a tunnel.
- **Prebuilt image:** to skip the local build, uncomment `image: ghcr.io/ustalabs/supermux:latest` in `docker-compose.yml` — it is published automatically on version tags.

> **Prefer guided setup — Docker *or* a native install?** Paste [`SETUP.md`](./SETUP.md) into Claude Code, Claude.ai, or ChatGPT and it walks a human through it, step by step.

## What you get

**One assistant, many agents**
- Drive **Claude Code, Codex, and Cursor** behind a single chat and web UI, with **live model switching** per session.
- **Personal-assistant (PA) sessions** are always-on orchestrators: talk to one and it can `spawn`, `kill`, `rename`, route to, and coordinate **worker** sessions for you — you describe the work, it fans it out.
- **Parallel by default** — one session per project or task, each isolated to its own working directory, each with its own conversation thread that resumes with full history after a restart.

**Two surfaces, made for the job**
- **Telegram** — lightweight and mobile-first: one chat multiplexed across all sessions, `/switch` between them, voice notes, photos/files, reactions, message edits.
- **Web PWA** — the power surface: a separate chat per session, installable to your home screen, with a real code editor, terminal, and voice input built in.

**A real workspace in the browser**
- **Code editor** — CodeMirror with a file tree, syntax highlighting (TS/JS, Python, JSON, HTML/CSS, Markdown, Vue…), search, multi-tab editing, save-to-disk, and a "file changed on disk" reload prompt when an agent edits what you have open.
- **Git diff view** — see every uncommitted change *since the session started*, grouped by repo (it finds nested repos too), with per-file expandable diffs.
- **Integrated terminal** — a genuine PTY (xterm.js) in the session's working directory, with paste support that actually works on iOS.

**It reaches you**
- **Push notifications** (Web Push / VAPID) to your phone and desktop — intelligently suppressed while you're already looking at that session, mutable per session.
- **Voice, attachments, camera** — record a voice prompt with a live waveform, drag in files, or snap a photo straight into the composer.

**Yours to own**
- **Self-hosted, no relay** — your machine, your tokens, your data. Bring your own agent credentials — the setup wizard handles authentication on first run.
- **Shared memory** — a file-based `~/.mux/` that every session can read and write: accumulated per-topic knowledge, project conventions, and a `soul.md` that gives your assistant a consistent identity you define (it ships blank — no preset persona).
- **Usage dashboard** — rate-limit and spend across Claude, Codex, and Cursor in one view.
- **Built-in reverse proxy** *(advanced)* — an agent can `expose_port` and get a public HTTPS URL for the app it's building (device-paired by default; per-proxy toggle or `set_proxy_public` to open without auth). WebSocket/HMR included.

## Who it's for

- **Solo devs & indie hackers** who run agents for real work and want to start, steer, and unblock them from anywhere — not just at the desk.
- **Freelancers & small agencies** juggling several client codebases at once: many named sessions in one view, an orchestrator to distribute work, push when something needs you.
- **Privacy-first self-hosters** who already run their own stack and want a polished agent assistant with zero cloud middleman.
- **Small team leads** who want routine work delegated to agents on private repos, with visibility into what's running and notifications routed to the right person.

## How it works

```
  phone / laptop                  your machine
 ┌──────────────┐        ┌──────────────────────────────────┐
 │  Telegram     │        │  supermux broker                  │
 │  Web PWA      │ ◀────▶ │  routing · push · files · proxy   │
 └──────────────┘        │     │        │         │          │
                         │  tmux     tmux       tmux          │
                         │ ┌─────┐  ┌─────┐   ┌──────┐        │
                         │ │claude│  │codex│   │cursor│  …      │
                         │ └─────┘  └─────┘   └──────┘        │
                         └──────────────────────────────────┘
```

- **Broker** — one daemon that owns your channel credentials and supervises every session in tmux. Control commands (`/sessions`, `/spawn`, `/switch`, …) are handled by the broker, so routing burns no agent tokens.
- **Sessions** — each agent runs in its own tmux window; a small MCP shim proxies messages between broker and agent. PA sessions persist and orchestrate; workers are spawned per task and resume on demand.
- **Channels** — Telegram and/or the web PWA. Each session is its own thread; replies are pushed back to your device.

## Requirements

- **Linux** (or the Docker image above on any host).
- **tmux** on `PATH`, plus at least one agent CLI logged in / with credentials: `claude`, `codex`, or `cursor-agent`.
- A native install also needs [`bun`](https://bun.sh) and `git`. The broker runs fine **web-only** (no Telegram token) — set `MUX_WEB_PORT` + `MUX_WEB_PUBLIC_URL`.

## Configuration

Set these in `~/.mux/state/.env` (native) or `.env` (Docker):

| Variable | Purpose |
|---|---|
| `MUX_WEB_PORT`, `MUX_WEB_PUBLIC_URL` | Enable the web PWA (both required, or neither). |
| `MUX_TELEGRAM_BOT_TOKEN` | Enable Telegram (optional). |
| `MUX_PA_NAME` | Name of your personal-assistant session (default `assistant`). |
| `MUX_HOME`, `MUX_STATE_DIR` | Override where state lives (default `~/.mux` / `~/.mux/state`). |
| `MUX_PROXY_BASE_DOMAIN` | Enable the reverse proxy / public-URL feature (advanced). |

For a persistent native deployment there's a `systemd/mux.service` template, and [`SETUP.md`](./SETUP.md) covers the whole native path.

## Docs

The design specs under [`docs/superpowers/specs/`](docs/superpowers/specs/) are the by-hand reference for how and why supermux works — multi-agent support, the web channel, the reverse proxy, the memory system, and the open-source layout.

## License

[MIT](./LICENSE) — free and open source.

---

🤖 Made with ❤️ supermux ❤️
