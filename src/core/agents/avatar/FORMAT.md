# supermux Agent avatar format (v2, "rig")

An Agent's avatar is a small animated character drawn in the sidebar (34×34 dp, also larger in
chat headers). It is a JSON document: a stack of vector **parts** (SVG path data or simple
shapes), plus per-**state** keyframe **tracks** and built-in **behaviors**. The same JSON renders
identically in the app (Kotlin/Compose) and in the preview tool (`preview/render.py`).

Design for SMALL: it must read at 34 px. Big simple silhouette, strong contrast, a clear face.

## Document

```json
{
  "format": "supermux-avatar",
  "version": 2,
  "name": "Fox",
  "size": 100,
  "parts": [ /* Part, drawn in order: first = back */ ],
  "states": { "idle": {}, "working": {}, "waiting": {}, "asleep": {}, "hover": {} }
}
```

- `size`: the canvas is `0..size` on both axes (like an SVG viewBox `0 0 size size`). Default 100.
  Keep the character inside it; leave ~6 units margin so motion doesn't clip.

## Part

```json
{
  "id": "head",
  "shape": "path",
  "d": "M20 50 C20 25 80 25 80 50 C80 80 20 80 20 50 Z",
  "fill": "#FF8A3D",
  "stroke": "#5A2A00", "strokeWidth": 2,
  "opacity": 1,
  "pivot": [50, 60],
  "x": 0, "y": 0, "rotate": 0, "scale": 1, "scaleX": 1, "scaleY": 1,
  "behaviors": [ { "type": "blink" } ],
  "children": [ /* Parts drawn on top, moving with this part */ ]
}
```

| field | meaning |
|---|---|
| `id` | unique name; tracks and behaviors target parts by id |
| `shape` | `path` (default when `d` is set), `circle` (`cx`,`cy`,`r`), `ellipse` (`cx`,`cy`,`rx`,`ry`), `rect` (`x0`,`y0`,`w`,`h`, optional `corner`), `group` (draws nothing, only children) |
| `d` | SVG path data: M L H V C S Q T A Z, absolute or relative |
| `fill` | `"#RRGGBB"` / `"#RRGGBBAA"` / `"none"` / a gradient (below). **Default `#000000`** — a line (smile, "z") needs `"fill": "none"` or it gets a black filled crescent |
| `stroke`, `strokeWidth`, `lineCap` | outline; `lineCap` `butt`/`round`/`square` |
| `opacity` | 0..1 |
| `pivot` | `[x, y]` origin for rotate and scale, or `"center"` = the part's own centre (a group: the average of its children's centres). Default: the centre of the **canvas** — so a blinking eye at y=44 without a pivot slides toward y=50; use `"center"` |
| `keepColor` | `true`: the state's `saturation` doesn't grey this part (a glowing prop stays lit while asleep) |
| `x`,`y`,`rotate`,`scale`,`scaleX`,`scaleY` | base transform (rotate in degrees) |
| `children` | parts that inherit this part's transform and opacity. Note: a child is also drawn at its parent's depth (right after the parent) |

Gradients (coordinates in canvas units):
```json
{ "linear": { "from": [50, 20], "to": [50, 90], "stops": ["#FFB36B", "#FF6A00"] } }
{ "radial": { "center": [40, 40], "radius": 50, "stops": ["#FFFFFF", "#9B87FF"] } }
```
`stops` may also be `[[0, "#fff"], [0.7, "#abc"], [1, "#000"]]`.

## States

The app picks one state from the Agent's live status:

| state | when |
|---|---|
| `idle` | on, nothing happening |
| `working` | the Agent is busy (running tools, thinking, writing) |
| `waiting` | it replied and you haven't read it yet: it wants your attention |
| `asleep` | its session is not running |
| `listening` | voice chat: you are talking to it (it is hearing you) |
| `speaking` | voice chat: it is talking back to you |
| `hover` | **overlay**: added on top of the current state while the pointer is over it / it's open. It fades in and out over 300 ms, and its `loop: false` tracks start when the hover starts (one-shot reactions). `"hover:asleep"` (any `hover:<state>`) replaces `hover` in that state — e.g. a sleepy one-eye-open peek instead of a grin |

A missing state behaves like `idle` (except `hover`, which then adds nothing). `listening` and
`speaking` are for voice chat (coming soon) — design them now; until then they fall back the same way.
Switching state cross-fades over 300 ms.

```json
"working": {
  "tracks": [
    { "part": "tail", "prop": "rotate", "duration": 600, "ease": "inOut",
      "keys": [[0, -15], [0.5, 15], [1, -15]] }
  ],
  "saturation": 1,
  "opacity": 1,
  "disable": ["lookAt"]
}
```

### Track

| field | meaning |
|---|---|
| `part` | part id |
| `prop` | `x`, `y`, `rotate`, `scale`, `scaleX`, `scaleY`, `opacity`, `strokeWidth`, `fill`, `stroke` |
| `keys` | `[[t, value], …]` or `[t, value, ease]`, `t` from 0 to 1 across `duration`; `fill`/`stroke` values are colours. A key's own `ease` applies to the segment **starting** at that key (so a jump is just `[0.5, 0, "step"]`) |
| `duration` | ms per loop (default 1000) |
| `delay` | ms offset (lets several parts share a rhythm out of phase) |
| `loop` | default `true`; `false` plays once on entering the state and holds the last key |
| `ease` | `linear`, `in`, `out`, `inOut` (default), `step` |
| `driver` | `time` (default) or `level`: the live voice level 0..1 (your voice while `listening`, its own voice while `speaking`; 0 in every other state) picks the position in `keys` instead of the clock — `duration`, `delay` and `loop` are ignored. E.g. a mouth `scaleY` with keys `[[0, 0.2], [1, 1.4]]` opens with the sound |
| `gain` | `level`: a time-driven track whose motion **grows with the voice** — `x`/`y`/`rotate` values are multiplied by the level, `scale`-like values become `1 + (v − 1) × level`. Silence = no motion. ("Gesture harder when louder.") |

Track values **combine** with the part's base value: `x`, `y`, `rotate` are **added**;
`scale`, `scaleX`, `scaleY`, `opacity`, `strokeWidth` are **multiplied**; `fill`, `stroke` **replace**.
### The voice level

The level is **normalised loudness**, 0..1: about −50 dBFS → 0 and −10 dBFS → 1, smoothed by the
app (fast attack ~30 ms, slower release ~150 ms), so normal speech moves across most of the range
and a mouth won't flicker. It is 0 in every state except `listening` and `speaking`.

- Level tracks default to `ease: "linear"` (any other ease bends the level; `"out"` makes quiet
  speech more visible, which suits mouths).
- **Silence is the common case**: a listening/speaking state must still read at level 0, so give
  level tracks a non-zero floor (e.g. arcs at opacity 0.5, not 0).
- `ease: "step"` on a level track is a threshold gate: `[[0, 0, "step"], [0.7, 1]]` shows a part
  only when loud.

Several tracks on the same part and prop **stack** the same way (and with behaviors) — e.g. a slow
time-driven bob plus a level-driven boost on `y`.
State cross-fades take rotation the short way round. A gradient `fill` can't be keyframed (a
`fill` track replaces it with a flat colour).

Timing is offset per Agent (a seed from its id), so two Agents with the same avatar don't blink
or hop in lockstep — don't rely on two avatars being in sync.
So `y` keys `[[0,0],[0.5,-6],[1,0]]` mean "hop 6 units up and back".

State-level: `saturation` (0 = grey, 1 = normal) and `opacity` apply to the whole avatar.
`disable` turns off named behavior types (`blink`, `lookAt`, `breathe`, `float`) in that state.

## Behaviors (built-in, procedural)

Put on a part: `"behaviors": [ { "type": "blink" } ]`.

| type | effect | options |
|---|---|---|
| `blink` | squashes the part's `scaleY` to ~0.1 for ~140 ms every few seconds. Put it on **one group holding both eyes** with `"pivot": "center"` — each part has its own blink timer, so two eyes with separate `blink`s blink at different times | `every` (ms, default ≈3500, jittered per part) |
| `lookAt` | shifts the part toward the mouse pointer (eyes/pupils/head), drifts gently when there is no pointer | `range` (canvas units, default 3) |
| `breathe` | gentle scale pulse around the pivot | `amount` (default 0.03), `period` (ms, default 3200) |
| `float` | gentle vertical bob | `amount` (units, default 2), `period` (ms, default 2400) |

## Tips

- Group the head and face so one track moves both: put eyes/mouth in the head's `children`.
- Make each state clearly different at 34 px: motion (bob vs. shake vs. hop), eyes (open,
  closed, looking up), colour (asleep → `"saturation": 0.3`), and props that appear only in one
  state (a "z", a "!", sweat drops, a laptop…) using `showIn`.

### Show/hide a part per state

`"showIn": ["asleep"]` on a part draws it only in those states (it fades with the cross-fade).
`"hover"` works here too: `"showIn": ["hover"]` for a hover-only sparkle or blush.
`"hideIn": ["asleep"]` is the opposite (e.g. open eyes hidden while asleep, closed eyes shown).

## Preview and save (for Agents)

- `preview_avatar({ json })` or `preview_avatar({ path })` validates and renders a PNG contact
  sheet without saving: per state (idle, working, waiting, asleep, listening, speaking,
  idle+hover), 8 frames across 2 s plus a **blink** frame, at 120 px clipped to the canvas like
  the app; then every state at the real sidebar size (34 px, magnified ×3) on dark and light.
  `listening`/`speaking` frames use a simulated voice level. Open the PNG and LOOK at it.
- `set_avatar({ json })` / `set_avatar({ path })` (Agents only) validates, saves it as your
  avatar and updates every open app at once. Invalid avatars are refused with the errors.
- Problems listed: unknown parts, props, states, shapes, eases, behaviors, bad values, bad path
  data. Lines starting `warning:` are advisory. Limits: 64 KB, 150 parts.

Outside supermux: `bun src/core/agents/avatar/tools/preview.ts avatar.json out.png` renders
the same sheet from the repo.
