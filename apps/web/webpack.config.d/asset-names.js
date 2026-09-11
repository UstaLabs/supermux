// Emit the wasm assets under their SOURCE names (skiko.wasm, supermux-apps-web.wasm) instead of
// webpack's default content hash.
//
// Why: `stageForBroker` does the content hashing itself, and it rewrites references by matching the
// emitted file name. Skiko's loader also carries a bare `"skiko.wasm"` string for its Emscripten
// `locateFile` fallback path; with webpack's opaque hash that literal can never be rewritten and a
// stale, unhashed URL survives in the bundle. Keeping the real names makes every reference — the
// webpack asset URL and the locateFile fallback — one and the same token, so one rewrite fixes both.
config.output = config.output || {};
config.output.assetModuleFilename = "[name][ext]";
