# Releasing

A release is a pushed `v*` tag. `.github/workflows/release.yml` builds everything; the **tag name
picks the channel**, and CI refuses a tag cut from the wrong branch.

| | Stable | Alpha |
|---|---|---|
| Tag | `v0.12.0` | `v0.12.0-alpha.1`, `-alpha.2`, … |
| Cut from | `main` | `dev` |
| GitHub Release | normal (becomes `releases/latest`) | pre-release (never `latest`) |
| Docker | `:v0.12.0` + `:latest` | `:v0.12.0-alpha.1` + `:alpha` |
| `supermux.dev/versions.json` | `channels.stable` (and `channels.alpha`, unless a newer alpha is out) | `channels.alpha` only — `channels.stable` is carried over untouched |
| Pinned `docker-compose.yml` on supermux.dev | rewritten | not touched |
| npm `@supermux/core` + `supermux-core` | `0.12.0` as `latest` | `0.12.0-alpha.1` as `next` |

## The core on npm

`@supermux/core` and its `supermux-core` alias ship with every tag, at the tag's version: the
`publish-core` job in `release.yml` stamps it (`scripts/set-core-version.mjs`) into both packages,
`LIBRARY_VERSION` and the examples, runs the core's checks, then publishes. The version in the
source is not bumped by hand. It runs after the GitHub Release, so a failed lane publishes nothing;
a rerun skips a version that is already on npm. Publishing uses npm trusted publishing: each
package's trusted publisher on npmjs.com must be GitHub, `UstaLabs/supermux`, `release.yml`.

## Cutting an alpha

```sh
git checkout dev && git pull
git tag v0.12.0-alpha.1 && git push origin v0.12.0-alpha.1
```

## Cutting a stable

```sh
git checkout main && git merge dev && git push
git tag v0.12.0 && git push origin v0.12.0
```

## Who gets prompted

A build follows a channel according to **its own version**: a build whose version has a
pre-release suffix (`0.12.0-alpha.1`) reads `channels.alpha`; every other build reads
`channels.stable`. So:

- Opting in to the alpha = installing an alpha build (from the GitHub pre-release). From then on
  it updates alpha → alpha → the next stable on its own.
- A stable install is never offered an alpha — nothing it reads changes on an alpha tag.
- Leaving the alpha = reinstalling a stable build. An alpha may have migrated `~/.mux`, so
  there is no automatic downgrade.

Pre-release numbers compare numerically (`alpha.9 < alpha.10`). Keep the `alpha.N` shape.

`workflow_dispatch` is a dry run: it builds and smokes everything and publishes nothing.
