# ─── supermux – Docker "taste-test" image ────────────────────────────────────
#
# Lets anyone spin up the broker on their laptop with a single
# `docker compose up`.
#
# Three stages, each for one reason: two build toolchains the runtime image must
# not carry, and the runtime image itself.
#   0.  `webbuild` (eclipse-temurin) — the web client is a Kotlin/Wasm Compose
#       app, so BUILDING it needs a JDK + the Gradle/Kotlin toolchain (hundreds
#       of MB the broker never executes).
#   0b. `zmxbuild` (debian + pinned Zig) — the workspace-terminal daemon and its
#       broker helper, compiled from the vendored pin. A Zig toolchain and a
#       Ghostty checkout are likewise nothing the runtime needs.
#   1.  oven/bun — the runtime image, which only COPYs the two stages' outputs
#       across. It is otherwise deliberately plain — full source, full
#       node_modules, no tree-shaking — so it stays easy to follow and debug.
#
# Prerequisites on the HOST (brought in by the user, NOT baked in):
#   • An Anthropic account — run `docker compose exec broker claude login`
#     the first time; auth is persisted in the `claude-auth` named volume.
#
# Agent CLIs (all three baked in; users only need to supply auth):
#   • claude-code:  @anthropic-ai/claude-code (npm)
#   • codex:        @openai/codex (npm)
#   • cursor-agent: installed via https://cursor.com/install
# ─────────────────────────────────────────────────────────────────────────────

# ── 0. Web client build stage ─────────────────────────────────────────────────
# `:web:stageForBroker` compiles the Kotlin/Wasm Compose app and writes the
# content-hashed bundle + PWA shell into src/channels/web/static. It needs a
# JDK 17+; Gradle fetches the Kotlin/Wasm toolchain and its yarn workspace
# itself (so this stage needs network, and is the slow one — cache it).
FROM eclipse-temurin:17-jdk AS webbuild
WORKDIR /src
# libatomic1: the Kotlin Gradle plugin downloads its own Node (v25) to run the
# yarn install behind `:kotlinWasmNpmInstall`, and that binary is linked against
# libatomic.so.1, which this JDK image does not ship. Without it the whole build
# dies at `:kotlinWasmNpmInstall` with "returns 127" eight minutes in.
RUN apt-get update \
 && apt-get install -y --no-install-recommends libatomic1 \
 && rm -rf /var/lib/apt/lists/*
# The Gradle build lives entirely under apps/ …
COPY apps ./apps
# … except for one cross-tree assertion: stageForBroker refuses to publish
# unless its output dir's sibling `static-serve.ts` exists, so that the task can
# never scribble a bundle into some unrelated directory. Copy that one file.
COPY src/channels/web/static-serve.ts ./src/channels/web/static-serve.ts
RUN cd apps && ./gradlew :web:stageForBroker --no-daemon --console=plain

# ── 0b. Workspace-terminal backend build stage ───────────────────────────────
# The image ships the PINNED, PATCHED zmx and its framed broker helper, built here
# from vendor/zmx/upstream.lock.json. It never installs "a zmx" — not at build time
# and emphatically not at container startup:
#
#   * the broker verifies both binaries against the manifest built beside them
#     before it execs either (src/core/terminal/zmx/helper.ts), so an arbitrary
#     upstream build would simply be refused;
#   * the patch this repo carries (an explicit restore boundary, broker-chosen
#     size ownership) is not in any released zmx;
#   * and a container that fetched its own terminal backend on boot would give
#     two identically-tagged images two different terminals.
#
# --platform=$BUILDPLATFORM: this stage runs on the BUILDER's architecture and
# cross-compiles with Zig's own target support. The multi-arch release build does
# linux/amd64 + linux/arm64, and compiling Ghostty under QEMU emulation is hours
# per arch — the cross build is minutes.
FROM --platform=$BUILDPLATFORM debian:bookworm-slim AS zmxbuild
ARG TARGETARCH
# python3-cryptography is not optional: the Zig installer verifies the pinned
# tarball's minisign signature and FAILS CLOSED without it (the sha256 pin alone
# is only accepted behind an explicit override, which a release image must not use).
RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      ca-certificates curl git python3 python3-cryptography xz-utils \
 && rm -rf /var/lib/apt/lists/*
WORKDIR /src
# Only what the build reads: the pin + patch, the helper sources, the two scripts,
# and terminal-core's Zig provisioner (the ONE place a Zig toolchain comes from).
COPY vendor/zmx ./vendor/zmx
COPY src/core/terminal/zmx/helper ./src/core/terminal/zmx/helper
COPY scripts/build-zmx.sh scripts/check-zmx-bundle.sh ./scripts/
COPY apps/terminal-core/native/common.sh apps/terminal-core/native/upstream.lock.json ./apps/terminal-core/native/
RUN set -eu; \
    case "$TARGETARCH" in \
      amd64) zmx_target=linux-x64 ;; \
      arm64) zmx_target=linux-arm64 ;; \
      *) echo "no pinned zmx target for TARGETARCH=$TARGETARCH" >&2; exit 1 ;; \
    esac; \
    MUX_ZIG_JOBS=4 bash scripts/build-zmx.sh --target "$zmx_target" --no-test --out /opt/supermux/zmx; \
    bash scripts/check-zmx-bundle.sh /opt/supermux/zmx "$zmx_target"

# ── 1. Base image ─────────────────────────────────────────────────────────────
# oven/bun:1 is the official Bun image based on Debian Bookworm.
FROM oven/bun:1

# ── 2. System dependencies ────────────────────────────────────────────────────
# • tmux   — AGENT sessions only. Workspace terminals moved to zmx (stage 0b) and
#            no longer touch tmux at all, but every agent session still runs inside
#            one, and the Claude native-terminal retirement that would remove it
#            from the product has not landed. Removing tmux here would break agents.
# • bash, ncurses-term — what a workspace terminal actually needs to be usable: a
#            login shell to run, and the terminfo entry for the TERM the broker
#            hands the child. The daemon forces TERM=xterm-256color
#            (src/core/terminal/zmx/backend.ts); without ncurses-term that is an
#            unknown terminal, and every curses program in the container — vim,
#            top, less — either refuses to start or draws nothing.
# • git    — useful inside spawned sessions; some agent CLIs call it at startup
# • ca-certificates, curl — baseline TLS + downloads
# • nodejs, npm — needed to run the `claude` CLI (it's a Node.js binary)
RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      tmux \
      bash \
      ncurses-term \
      git \
      ca-certificates \
      curl \
      nodejs \
      npm \
 && rm -rf /var/lib/apt/lists/*

# ── 3. Agent CLIs (all three installed by default) ───────────────────────────
# Claude Code + Codex via npm; Cursor CLI via its install script. Users still
# bring their own auth (via the in-app wizard); these just put the binaries on PATH.
#
# Claude is PINNED to a known-good version: 2.1.161 has a bug where loading dev
# channels re-shows the "Bypass Permissions mode" prompt (despite the pre-accept
# flag) — the broker's consent Enter then hits its "No, exit" default and the PA
# dies on every spawn. 2.1.162 fixes it. Bump this pin to adopt newer Claude.
RUN npm install -g @anthropic-ai/claude-code@2.1.162 @openai/codex
# Cursor CLI installs to /root/.local/bin; make it available on PATH.
RUN curl -fsS https://cursor.com/install | bash || echo "cursor-agent install failed (non-fatal)"
ENV PATH="/root/.local/bin:${PATH}"

# The container runs as root, and `claude --dangerously-skip-permissions` (how the
# broker spawns Claude) refuses to run as root unless IS_SANDBOX is set. The whole
# container IS a sandbox, so declare it — otherwise every Claude session exits
# immediately ("cannot be used with root/sudo privileges") and can't respond.
ENV IS_SANDBOX=1

# Pin the Claude version deterministically: auto-update caused version drift
# between containers (one stayed on the buggy 2.1.161, another healed to 2.1.162)
# and — worse — when a buggy version kills the PA on spawn, it never lives long
# enough to auto-update out of the bug (a stuck loop). Disable it so the pinned,
# known-good version above is exactly what runs. (The broker also accepts the
# bypass prompt as a belt-and-suspenders backup; see post-spawn-keys.)
ENV DISABLE_AUTOUPDATER=1

# ── 4. App source & dependencies ──────────────────────────────────────────────
WORKDIR /app

# Copy manifest files first so Docker can cache the install layer separately
# from source changes. bun.lock is copied via a glob and the install is
# non-frozen on purpose: a user building from a tarball (no lock) must still get
# a working `docker compose up` rather than "lockfile not found".
COPY package.json bun.lock* ./

# Install root dependencies (non-frozen: the root lock may be absent)
RUN bun install

# Copy the rest of the source tree
COPY . .

# ── 5. Web UI ─────────────────────────────────────────────────────────────────
# src/channels/web/static is gitignored, so it is NOT in the build context — it
# comes from the JDK stage above. Nothing but this COPY puts a web UI in the
# image; without it the broker boots and serves 404 for the shell.
COPY --from=webbuild /src/src/channels/web/static ./src/channels/web/static

# ── 5b. Workspace-terminal backend ────────────────────────────────────────────
# The verified bundle from stage 0b, at a fixed path the broker is TOLD about
# rather than one it searches for: MUX_ZMX_BIN_DIR names the directory, so a
# `zmx` a user later installs into the container can never become what their
# workspace terminals run. Nothing is fetched or installed at startup.
COPY --from=zmxbuild /opt/supermux/zmx /opt/supermux/zmx
ENV MUX_ZMX_BIN_DIR=/opt/supermux/zmx

# ── 6. Runtime defaults ───────────────────────────────────────────────────────
# MUX_WEB_PORT is the port the broker's HTTP server listens on inside the container.
# docker-compose.yml maps it to 8787 on the host.
ENV MUX_WEB_PORT=8787

# Expose the broker web port
EXPOSE 8787

# ── 7. Entrypoint ─────────────────────────────────────────────────────────────
# HOME is /root in this image; broker state lands in /root/.mux and claude
# auth in /root/.claude — both should be backed by named volumes (see compose).
CMD ["bun", "src/main.ts"]
