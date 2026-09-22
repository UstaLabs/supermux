# CodeMirror 6 bundle for the Android editor

`../src/main/assets/editor/cm6.js` is a generated CodeMirror 6 bundle used by the
WebView code editor (`WebCodeEditor.kt`). It mirrors the retired Vue web editor's
CodeMirror setup (see git history before 2026-09-12) with a curated,
statically-imported language set — no dynamic imports, since a `file://` WebView
origin can't load split chunks.

This directory is a self-contained package: `package.json` + `bun.lock` pin every
dependency the bundle is built from, so the committed `cm6.js` is reproducible
without any other workspace in the repo.

## Rebuild

```sh
cd apps/android/codemirror
bun install --frozen-lockfile
bun run build            # writes ../src/main/assets/editor/cm6.js
git diff --stat ../src/main/assets/editor/cm6.js   # expected: no change
```

`tests/cm6-bundle-drift.test.ts` (root `bun test`) rebuilds the entry into a temp
directory and asserts the result is byte-identical to the committed `cm6.js`; it
skips itself when `node_modules` here is missing.

## Version pins

`dependencies` pins the 26 direct `@codemirror/*` packages at exact versions (no
carets). `overrides` additionally pins a handful of transitive packages
(`@lezer/*` grammars, `crelt`, `@marijn/find-cluster-break`, the
`vscode-languageserver-*` trio) to the versions that produced the committed
bundle — without them a fresh `bun install` floats those to newer releases and
the bundle bytes change. Bumping any pin is fine, but it is a bundle change:
rebuild, re-run `:desktop:test` (`EditorWebAssetsTest`) and exercise the editor
(open a file, type, Ctrl+S) before committing the new bytes.

The bundle exposes globals `cmInit / cmSetContent / cmGetContent / cmSetLineWrap /
cmSetFontSize / cmSetLanguage` and calls back into the `AndroidEditor` JS
interface (`onChange` / `onSave` / `onReady`). The host page is
`../src/main/assets/editor/index.html`.
