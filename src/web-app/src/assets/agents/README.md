# Agent logos

One flat 24x24 SVG per agent, `<name>.svg` (light UI) + `<name>-dark.svg` (dark UI),
wired up in `src/web-app/src/components/AgentLogo.vue`. Fills are `#1a1a1a` (light)
and `#ededed` (dark); an agent with no logo renders nothing, so a missing file
degrades to a bare label rather than breaking.

`grok.svg` / `grok-dark.svg` are an in-house angular "X" approximation of the xAI
mark, not xAI's official artwork — no logo ships with the grok CLI. To use the real
thing, overwrite both files with the official path (keep the 24x24 viewBox and the
two fills) and the swap is picked up everywhere; also replace
`apps/iosApp/Supermux/Assets.xcassets/grok.imageset/grok.svg`.
