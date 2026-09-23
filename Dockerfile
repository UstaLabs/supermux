# ─── supermux – Docker "taste-test" image ────────────────────────────────────
#
# Lets anyone spin up the broker on their laptop with a single
# `docker compose up`.
#
# Two stages, for one reason only: the web client is a Kotlin/Wasm Compose app,
# so BUILDING it needs a JDK + the Gradle/Kotlin toolchain (hundreds of MB that
# the broker never executes at runtime). Stage 1 (`webbuild`, eclipse-temurin)
# compiles the bundle; stage 2 (oven/bun) is the runtime image and only COPYs
# the staged output across. The runtime stage is otherwise deliberately plain —
# full source, full node_modules, no tree-shaking — so it stays easy to follow
# and debug.
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
#
# --platform=$BUILDPLATFORM: the bundle is wasm/js/html — identical for every target arch — so
# build it ONCE, natively. Without this the multi-arch release build runs Gradle a second time
# under QEMU arm64 emulation, which takes hours instead of minutes.
FROM --platform=$BUILDPLATFORM eclipse-temurin:17-jdk AS webbuild
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

# ── 1. Base image ─────────────────────────────────────────────────────────────
# oven/bun:1 is the official Bun image based on Debian Bookworm.
FROM oven/bun:1

# ── 2. System dependencies ────────────────────────────────────────────────────
# • tmux   — required for the in-app terminal feature only (agent sessions run on Core)
# • git    — useful inside spawned sessions; some agent CLIs call it at startup
# • ca-certificates, curl — baseline TLS + downloads
# • nodejs, npm — needed to run the `claude` CLI (it's a Node.js binary)
RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      tmux \
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
# The broker source-imports packages/supermux-core; its manifest must be present
# before install so Bun resolves the workspace (ACP SDK etc.).
COPY packages/supermux-core/package.json ./packages/supermux-core/

# Install root + workspace dependencies (non-frozen on purpose, see above)
RUN bun install

# Copy the rest of the source tree
COPY . .

# ── 5. Web UI ─────────────────────────────────────────────────────────────────
# src/channels/web/static is gitignored, so it is NOT in the build context — it
# comes from the JDK stage above. Nothing but this COPY puts a web UI in the
# image; without it the broker boots and serves 404 for the shell.
COPY --from=webbuild /src/src/channels/web/static ./src/channels/web/static

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
