<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="src/web-app/public/icons/icon-512.png">
    <img src="assets/logo/supermux.svg" alt="supermux logo" width="120">
  </picture>
</p>

<h1 align="center">supermux</h1>

<p align="center"><strong>AFK. Still shipping.</strong></p>

<p align="center">
  The open-source, mobile-first agentic development environment.<br>
  Run Claude Code, Codex, Cursor, and OpenCode on hardware you control—then steer, review, and merge from anywhere.
</p>

<p align="center">
  <a href="https://github.com/UstaLabs/supermux/releases/latest"><img src="https://img.shields.io/github/v/release/UstaLabs/supermux?label=release" alt="Latest release"></a>
  <a href="https://apps.apple.com/app/supermux/id6782643917"><img src="https://img.shields.io/badge/App_Store-iPhone_%C2%B7_iPad_%C2%B7_Watch-0D96F6?logo=apple&logoColor=white" alt="Download on the App Store"></a>
  <a href="https://github.com/UstaLabs/supermux/releases/latest/download/supermux-android.apk"><img src="https://img.shields.io/badge/Android-APK-3DDC84?logo=android&logoColor=white" alt="Download Android APK"></a>
  <a href="./LICENSE"><img src="https://img.shields.io/badge/license-MIT-yellow" alt="MIT license"></a>
</p>

<p align="center">
  <a href="https://supermux.dev"><strong>Website</strong></a> ·
  <a href="#start-here"><strong>Get started</strong></a> ·
  <a href="./SETUP.md"><strong>Setup guide</strong></a> ·
  <a href="https://github.com/UstaLabs/supermux/releases/latest"><strong>Downloads</strong></a>
</p>

---

## Your agents should not be tied to your chair

Coding agents do their best work in long-running sessions. A laptop lid, a commute, or one unanswered question should not stop them.

supermux gives Claude Code, Codex, Cursor, and OpenCode a persistent home on your Mac, Linux machine, home server, or VPS. Native apps on your phone and computers become the control plane: start work, answer questions, inspect the diff, use the terminal, and decide what gets merged.

Your subscriptions. Your repositories. Your hardware. No supermux account required.

## What you can do

- **Run agents around the clock.** Sessions survive closed laptops, lost connections, broker restarts, and host reboots.
- **Carry the whole workspace.** Chat, voice, files, code editor, full PTY terminal, usage, and session activity are available from one interface.
- **Review before anything lands.** Every worker gets an isolated git worktree. Read the diff, leave inline feedback, run verification, then merge locally, open a PR, keep the branch, or discard it.
- **Control more than one host.** Pair a Mac, Linux workstation, home server, and VPS into one fleet. Offline hosts retain their last-known session snapshot.
- **Leave without missing the handoff.** Push notifications take you directly to the host and session that needs you.
- **Use the agent that fits the task.** Claude Code, Codex, Cursor, and OpenCode share the same workflow, and model or reasoning settings can change without starting over.
- **Keep context between sessions.** Shared project notes, domain memory, reusable skills, and an identity file let knowledge compound instead of resetting every chat.

## One workspace, every screen

| Platform | Download | What it does |
|---|---|---|
| iPhone, iPad, Apple Watch | [App Store](https://apps.apple.com/app/supermux/id6782643917) | Native mobile control, multi-pane iPad workspace, push, and Watch actions |
| Android phones, tablets, foldables | [Latest APK](https://github.com/UstaLabs/supermux/releases/latest/download/supermux-android.apk) | Native Compose client with phone and multi-pane large-screen layouts |
| macOS (Apple silicon) | [Latest DMG](https://github.com/UstaLabs/supermux/releases/latest/download/supermux-macos.dmg) | Native client that can also turn this Mac into a persistent host |
| Linux (x64) | [Latest DEB](https://github.com/UstaLabs/supermux/releases/latest/download/supermux-linux.deb) | Desktop client with a bundled local broker and host onboarding |
| Windows (x64) | [Latest MSI](https://github.com/UstaLabs/supermux/releases/latest/download/supermux-windows.msi) | Native desktop client; host agents through WSL2 or another paired machine |
| Any modern browser | Served by your host | Installable PWA with chat, editor, diff review, terminal, and administration |
| Telegram | Optional bot connection | Lightweight chat, notifications, attachments, voice, and session control |

## Start here

### Host on a Mac or Linux computer

1. Install and sign in to at least one supported agent CLI: `claude`, `codex`, `cursor-agent`, or `opencode`. Claude Code also needs `tmux` available on the host.
2. Install the [macOS app](https://github.com/UstaLabs/supermux/releases/latest/download/supermux-macos.dmg) or [Linux desktop app](https://github.com/UstaLabs/supermux/releases/latest/download/supermux-linux.deb).
3. Follow the first-run host wizard. It starts the local broker, offers to keep it available after sign-in, and shows a pairing QR.
4. Install Supermux on [iPhone or iPad](https://apps.apple.com/app/supermux/id6782643917) or [Android](https://github.com/UstaLabs/supermux/releases/latest/download/supermux-android.apk), then scan the QR.

The desktop host connects directly on your local network and can use the supermux connectivity relay when you are away.

### Host on a server, mini PC, or WSL2

**macOS / Linux**

```sh
curl -fsSL https://supermux.dev/install.sh | sh
```

**Windows** (PowerShell; installs the host in WSL2)

```powershell
irm https://supermux.dev/install.ps1 | iex
```

The installer configures the broker, connectivity, and first device. For a manual source install, reverse proxy, custom domain, or advanced agent authentication, use the [guided setup](./SETUP.md).

### Run with Docker

```sh
git clone https://github.com/UstaLabs/supermux.git
cd supermux
docker compose up -d
```

Open `http://localhost:8787`. State persists in named volumes; repositories mounted under `./workspace` are available to agents. To skip the local image build, enable the `ghcr.io/ustalabs/supermux:latest` image in [`docker-compose.yml`](./docker-compose.yml).

## How it works

```text
 iPhone · Android · Mac · Windows · Linux · Web · Telegram
                              │
                    direct connection or relay
                              │
                 ┌────────────▼────────────┐
                 │ a host you control      │
                 │                         │
                 │ supermux broker         │
                 │ routing · push · files  │
                 │ worktrees · review      │
                 │                         │
                 │ persistent agent runs   │
                 │ Claude · Codex · Cursor │
                 │ OpenCode                │
                 └─────────────────────────┘
```

The broker is the source of truth. It runs on the host, supervises agent sessions, keeps their histories, routes messages to the right client, and exposes the workspace APIs. Control operations happen in the broker, so switching or inspecting sessions does not spend agent tokens.

Each worker is isolated in its own git worktree. The main checkout moves only through the finish action you choose. Optional personal-assistant sessions can be created from Settings when you want an always-on orchestrator; fresh installs do not create one.

## Connectivity and ownership

- **Local first.** Clients can connect over LAN, VPN, your own reverse proxy, or your own tunnel.
- **Remote when you need it.** The built-in connectivity relay gives desktop hosts a stable HTTPS address without opening a router port.
- **Clear trust boundary.** Repositories, credentials, session history, and broker state remain on your host. Relay connections are encrypted in transit, but relay traffic is not yet end-to-end encrypted at the application layer.
- **Pairing, not accounts.** New devices join with a short-lived, single-use claim bound to the host identity. A client can keep several independently owned hosts in one fleet.
- **Open source.** The broker and clients are MIT licensed; the hosted relay is optional.

## Configuration

Native installs read `~/.mux/state/.env`; Docker reads `.env` in the project directory.

| Variable | Purpose |
|---|---|
| `MUX_WEB_PORT`, `MUX_WEB_PUBLIC_URL` | Enable the browser client and set its public origin |
| `MUX_TELEGRAM_BOT_TOKEN` | Enable the optional Telegram channel |
| `MUX_HOME`, `MUX_STATE_DIR` | Override the default `~/.mux` state locations |
| `MUX_RELAY_DOMAIN`, `MUX_RELAY_BASE` | Configure the optional connectivity relay |
| `MUX_PROXY_BASE_DOMAIN` | Enable per-session app previews through the broker proxy |

See [`.env.example`](./.env.example) for a Docker configuration example and the [setup guide](./SETUP.md) for advanced deployments.

## Built with supermux

supermux is developed from inside supermux. Its agents research, write plans, implement across TypeScript, Kotlin, Swift, and Compose, run reviews, and prepare releases while the maintainer steers from whichever screen is nearby.

## License

[MIT](./LICENSE) — free and open source.
