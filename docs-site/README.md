# supermux docs

The documentation website for supermux and `@supermux/core`, built with [Fumadocs](https://fumadocs.dev) on Next.js. It builds to a static site in `out/` that any static host can serve.

## Work on it

```sh
cd docs-site
bun install
bun run dev        # http://localhost:3000
```

Pages are MDX files in `content/pages/`:

- `content/pages/(supermux)/`: the **supermux** tab, for people who use supermux. User level, no internals.
- `content/pages/core/`: the **supermux core** tab, for developers building on `@supermux/core`.

Each folder's `meta.json` sets the order of the sidebar. A page must be listed there to appear.

## Build

```sh
bun run build      # writes the static site to out/
bun run start      # serves out/ on http://localhost:3000
```

| Variable | Effect |
|---|---|
| `DOCS_BASE_PATH` | Build for a sub-path, like `/p/docs-preview`. Leave it unset for a site at the root of its domain. |
| `DOCS_DRAFT=1` | Show the `<Check>` notes: open questions left for review. Never set it for a public build. |
| `NEXT_PUBLIC_SITE_URL` | The public address, used for page links shared with AI tools. Defaults to `https://docs.supermux.dev`. |

`bun run checks` lists every open `<Check>` note.

## Writing

- Every statement must match the product as it is in the code today. If you can't confirm something, write a `<Check>` note instead of guessing.
- Plain, friendly and short. Use the app's exact labels in **bold**, and menu paths like **Settings ▸ Providers**.
- The supermux tab doesn't cover Telegram or WhatsApp.

Components you can use in MDX: `Callout`, `Cards`/`Card`, `Steps`/`Step`, `Tabs`/`Tab`, `Accordions`/`Accordion`, `TypeTable`, `Files`, `Kbd`, `AgentGrid`, `AgentLogo`, `AvatarGallery`, `AvatarStates`, `HostDiagram` and `Check`.

The character images in `public/images/avatars/` are rendered with the app's own avatar renderer (`src/core/agents/avatar/renderer.js`) from `src/core/agents/avatar/gallery/`.

## Publishing

`.github/workflows/docs.yml` builds this folder and publishes it to GitHub Pages at **https://docs.supermux.dev**. It runs when a push to `main` changes `docs-site/`, and by hand from the Actions tab. The custom domain is set in the repository's Pages settings, so the site is built for the root: leave `DOCS_BASE_PATH` and `DOCS_DRAFT` unset there.
