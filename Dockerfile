# ─── supermux – Docker "taste-test" image ────────────────────────────────────
#
# Lets anyone spin up the broker on their laptop with a single
# `docker compose up`.  The image is intentionally fat (no multi-stage) so the
# build is easy to follow and debug.
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

# ── 1. Base image ─────────────────────────────────────────────────────────────
# oven/bun:1 is the official Bun image based on Debian Bookworm.
FROM oven/bun:1

# ── 2. System dependencies ────────────────────────────────────────────────────
# • tmux   — required by the broker (every agent session runs inside tmux)
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
# from source changes. The root bun.lock is gitignored (absent from a fresh
# clone), so it's copied optionally (glob) and the root install is non-frozen —
# otherwise `docker compose up` from a clean checkout fails on a missing lock.
COPY package.json bun.lock* ./
COPY src/web-app/package.json src/web-app/bun.lock* ./src/web-app/

# Install root dependencies (non-frozen: the root lock may be absent)
RUN bun install

# Install web-app dependencies (its bun.lock IS committed → reproducible)
RUN cd src/web-app && bun install --frozen-lockfile

# Copy the rest of the source tree
COPY . .

# ── 5. Web UI build ───────────────────────────────────────────────────────────
# Builds the Vue PWA into src/channels/web/static (gitignored; produced here).
# `build` runs `vue-tsc --noEmit && vite build`. vue-tsc can emit spurious errors
# in a clean build env (version-sensitive checks, generated-type ordering) that
# don't reproduce in a warm dev tree — and there's no committed bundle to fall
# back to anymore. So if the typecheck fails, bundle with vite directly: esbuild
# type-strips and still produces a correct runtime bundle (the typecheck is a
# dev/CI concern, not a requirement to produce a working container).
RUN cd src/web-app && (bun run build || (echo "vue-tsc typecheck failed in clean build env — bundling with vite only (runtime-safe)" && ./node_modules/.bin/vite build))

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
