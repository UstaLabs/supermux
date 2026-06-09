# CodeMirror 6 bundle for the Android editor

`src/main/assets/editor/cm6.js` is a generated CodeMirror 6 bundle used by the
WebView code editor (`WebCodeEditor.kt`). It mirrors the web app's
`CodeEditor.vue` setup (minus the LSP client) with a curated, statically-imported
language set — no dynamic imports, since a `file://` WebView origin can't load
split chunks.

## Rebuild

The source entry is `cm6-entry.mjs`. It bundles against the web app's installed
CodeMirror packages. From this repo (with `src/web-app/node_modules` installed):

```sh
mkdir -p /tmp/cmbuild
ln -sfn "$PWD/src/web-app/node_modules" /tmp/cmbuild/node_modules
cp apps/android/codemirror/cm6-entry.mjs /tmp/cmbuild/
bun build /tmp/cmbuild/cm6-entry.mjs \
  --outfile apps/android/src/main/assets/editor/cm6.js \
  --target browser --format iife --minify
```

The bundle exposes globals `cmInit / cmSetContent / cmGetContent / cmSetLineWrap /
cmSetFontSize / cmSetLanguage` and calls back into the `AndroidEditor` JS
interface (`onChange` / `onSave` / `onReady`). The host page is
`src/main/assets/editor/index.html`.
