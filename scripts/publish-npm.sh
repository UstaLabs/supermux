#!/usr/bin/env bash
# Publishes the npm package in the folder $1 with provenance, unless that exact version is already on the
# registry: a rerun of a release that failed halfway then publishes only what is missing. A version with a
# `-` in it (0.2.0-rc.1) is a prerelease and goes out under the `next` tag, never as `latest`.
#
# Run by .github/workflows/publish-core.yml. packages/supermux-core/e2e/publish.e2e.mjs runs it against a stub npm.
set -euo pipefail

cd "${1:?usage: publish-npm.sh <package folder>}"
name=$(node -p "require('./package.json').name")
version=$(node -p "require('./package.json').version")

# `npm view name@version version` prints the version when it exists, and fails with E404 when it does not.
if [ "$(npm view "$name@$version" version 2>/dev/null || true)" = "$version" ]; then
  echo "$name@$version is already on the registry; not publishing it again"
  exit 0
fi

echo "publishing $name@$version"
case "$version" in
  *-*) npm publish --provenance --access public --tag next ;;
  *) npm publish --provenance --access public ;;
esac
