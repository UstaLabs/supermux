# Telemetry

supermux sends anonymous usage events so we can see which agents, models, apps and
features people actually use. This page lists every event and every field. If something
isn't on this page, supermux doesn't send it.

## The short version

- **On by default.** Turn it off any time; see [Turning it off](#turning-it-off).
- **Sent by your broker only.** The desktop, phone and web apps don't send anything to us
  themselves. They tell your own broker what kind of app they are, and the broker decides
  what to send.
- **Goes to our own server only**, `telemetry.supermux.dev`. No analytics company, no
  advertising network, no third party.
- **No content.** No prompts, replies, code, file names, paths, project names or session
  names. No account IDs, no IP addresses.
- **You can see it.** Settings → System → Telemetry → **View recent events** shows the
  last 50 events exactly as they were sent.

## Never sent

- Your prompts, the agents' replies, or any message text
- Code, file contents, file names, folder paths, repository names or URLs
- Names of projects, workspaces, sessions or devices
- Session IDs, conversation IDs, or any ID from your agents
- Your email, your accounts with Anthropic, OpenAI or any other provider, or any login
- Passwords, API keys or tokens
- Your IP address. Our server uses it briefly to stop abuse and never stores it.
- Your hostname or your broker's host key
- Names of models you set up yourself (for example a local Ollama model); these are sent
  as `"custom"`

## What every event contains

| Field | Example | What it is |
|---|---|---|
| `uuid` | `6f1c2e0a-…` | Random ID for this one event, so a retried send isn't counted twice |
| `event` | `turn.completed` | Which event this is |
| `ts` | `2026-10-23T14:02:11.482Z` | When it happened |
| `installId` | `b7e4a1d2-…` | A random ID created when supermux first runs. It isn't derived from your machine, your accounts or your host key, and it is never linked to another install |
| `version` | `0.14.0` | supermux version |
| `commit` | `2e3ca970` | Build commit |
| `installMode` | `binary` | How supermux is installed: `binary`, `docker`, `source`, or `managed` (run by the desktop app) |
| `os` | `linux` | `linux`, `darwin` or `win32` |
| `arch` | `x64` | `x64` or `arm64` |

## Events

### `broker.started`

Sent when your broker starts.

```json
{
  "sessionCount": 14,
  "workspaceCount": 5,
  "agentsInstalled": ["claude", "codex", "opencode"],
  "channels": { "web": true, "telegram": true, "whatsapp": false }
}
```

| Field | What it is |
|---|---|
| `sessionCount` | How many sessions exist (a number only) |
| `workspaceCount` | How many workspaces exist (a number only) |
| `agentsInstalled` | Which agent CLIs are installed: `claude`, `codex`, `cursor`, `opencode`, `grok`, `antigravity` |
| `channels` | Which ways of talking to supermux are switched on |

### `broker.heartbeat`

Sent once every 24 hours while your broker runs, so we can count active installs. It has
the same fields as `broker.started`, plus:

| Field | What it is |
|---|---|
| `activeSessions24h` | How many sessions had a turn in the last 24 hours |

### `client.connected`

Sent when an app connects to your broker.

```json
{ "surface": "android", "clientVersion": "0.14.0", "clientOs": "android", "clientOsMajor": 16 }
```

| Field | What it is |
|---|---|
| `surface` | Which app: `android`, `ios`, `desktop`, `web`, `telegram` or `whatsapp` |
| `clientVersion` | That app's version |
| `clientOs` | `android`, `ios`, `macos`, `windows` or `linux` |
| `clientOsMajor` | The OS major version only (for example `16`), never the full build or the device model |

Telegram and WhatsApp send only `surface`.

### `session.created`

Sent when you start a session.

```json
{ "agent": "claude", "model": "claude-opus-5-5", "effort": "high", "fast": false }
```

| Field | What it is |
|---|---|
| `agent` | Which agent the session runs |
| `model` | The model the session runs (see [Model names](#model-names)) |
| `effort` | The reasoning effort, or `"default"` |
| `fast` | Whether fast mode is on |

### `turn.requested`

Sent when you send a message to a session. The message itself is not sent.

```json
{ "agent": "codex", "surface": "ios" }
```

`surface` is left out when the app you sent from didn't say which app it is (older versions).

### `turn.completed`

Sent when an agent finishes a turn. The reply itself is not sent.

```json
{
  "agent": "claude",
  "model": "claude-opus-5-5",
  "effort": "high",
  "fast": false,
  "status": "completed",
  "durationMs": 48210
}
```

| Field | What it is |
|---|---|
| `status` | `completed`, `error` or `interrupted` |
| `durationMs` | How long the turn took, from your message to the agent finishing. Left out if supermux didn't see the turn start |

No token counts are sent.

### `model.switched`

Sent when you change a session's model or effort.

```json
{
  "agent": "opencode",
  "fromModel": "anthropic/claude-sonnet-5-5",
  "toModel": "custom",
  "fromEffort": "medium",
  "toEffort": "medium"
}
```

### `feature.used`

Sent when you use one of the features below. Only the feature's name is sent, never
what you did with it: "a worktree was created", not which repository or branch.

| Field | What it is |
|---|---|
| `feature` | Which feature, from the list below |
| `variant` | Only for some features; always one of a fixed set of values |
| `agent` | Only for `permission_mode.set`: which agent the session runs |
| `surface` | Which app you used it from (same values as in `client.connected`) |

Every feature, with an example:

#### `worktree.create`

You start a session in a new worktree.

```json
{ "feature": "worktree.create", "surface": "desktop" }
```

#### `forge.clone`

You clone a repository from GitHub or GitLab. `variant` is the host: `github` or `gitlab`.

```json
{ "feature": "forge.clone", "variant": "github", "surface": "android" }
```

#### `forge.create`

You create a new repository. `variant`: `github`, `gitlab` or `local`.

```json
{ "feature": "forge.create", "variant": "local", "surface": "desktop" }
```

#### `workspace.create`

You create a workspace.

```json
{ "feature": "workspace.create", "surface": "web" }
```

#### `session.archive`

You archive a session.

```json
{ "feature": "session.archive", "surface": "ios" }
```

#### `session.mute`

You mute a session.

```json
{ "feature": "session.mute", "surface": "telegram" }
```

#### `terminal.open`

You open a terminal.

```json
{ "feature": "terminal.open", "surface": "desktop" }
```

#### `editor.open`

You open a file in the editor.

```json
{ "feature": "editor.open", "surface": "desktop" }
```

#### `file.write`

You save a file from the editor.

```json
{ "feature": "file.write", "surface": "desktop" }
```

#### `upload`

You attach a file.

```json
{ "feature": "upload", "surface": "ios" }
```

#### `display.open`

You open a remote display.

```json
{ "feature": "display.open", "surface": "android" }
```

#### `emulator.open`

You open an Android emulator or device.

```json
{ "feature": "emulator.open", "surface": "web" }
```

#### `voice.transcribe`

You dictate a message.

```json
{ "feature": "voice.transcribe", "surface": "android" }
```

#### `voice.speak`

You have a reply read aloud.

```json
{ "feature": "voice.speak", "surface": "ios" }
```

#### `search.messages`

You search your messages. The search text is not sent.

```json
{ "feature": "search.messages", "surface": "web" }
```

#### `proxy.expose`

You expose a port. The port number is not sent.

```json
{ "feature": "proxy.expose", "surface": "desktop" }
```

#### `permission_mode.set`

You change an agent's permission mode. `variant` is the mode, from that agent's own list (for example `bypass`, `accept-edits`, `ask`, `plan`, `auto`, `full-access`, `read-only`).

```json
{ "feature": "permission_mode.set", "variant": "plan", "agent": "claude", "surface": "web" }
```

#### `pair.device`

You pair a new device. Sent the first time the new device connects; `variant` is its app:
`android`, `ios`, `desktop` or `web`. There is no `surface` for this one.

```json
{ "feature": "pair.device", "variant": "ios" }
```

#### `update.run`

You update supermux from the app.

```json
{ "feature": "update.run", "surface": "desktop" }
```

## Model names

Model names are sent only when they come from the agent's own built-in list: for example
`claude-opus-5-5` from Claude Code, or `gpt-5.5` from Codex. OpenCode models are sent
only when they come from a hosted provider (Anthropic, OpenAI, Google, OpenRouter, xAI,
Groq, DeepSeek, Mistral, GitHub Copilot or OpenCode). Anything else, such as a local
model or a provider you named yourself, is sent as `"custom"`. A session with no model
picked is sent as `"default"`.

If you change the model inside the agent's own terminal (for example `/model` in Claude
Code), supermux doesn't see it, so later turns keep the model chosen in supermux.

## How it's sent

Your broker saves events in its own database and sends them in batches about once a
minute over HTTPS to `https://telemetry.supermux.dev/v1/batch`. If the server can't be
reached, the broker tries again later; after 5 failed tries a batch is deleted. At most
5,000 unsent events, and nothing older than 7 days, are kept. Turning telemetry off
deletes every unsent event right away.

Our server checks every event against this page. An event or field that isn't listed
here is rejected, not stored.

Development builds and test brokers never send telemetry.

## Turning it off

Any of these turns it off:

- **In the app:** Settings → System → Telemetry → off.
- **Environment variable:** `MUX_TELEMETRY=0`
- **The standard opt-out:** `DO_NOT_TRACK=1`

For a broker that runs as a service, put the variable in `~/.mux/state/.env`, then
restart supermux. An environment variable always wins over the in-app setting.

## Who sees it

Only the supermux maintainers. We use it to decide what to build and fix. We don't sell
it, share it, or combine it with data from anywhere else.

## Changes

Any change to what supermux sends will update this page in the same release, and will be
listed in the release notes.
