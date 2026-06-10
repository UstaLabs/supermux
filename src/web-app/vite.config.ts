import { defineConfig } from "vite"
import vue from "@vitejs/plugin-vue"
import tailwindcss from "@tailwindcss/vite"
import { VitePWA } from "vite-plugin-pwa"
import { execSync } from "child_process"
import { readFileSync } from "fs"
import { resolve } from "path"
import { PRODUCT_NAME } from "../shared/brand"

const webAppRoot = __dirname
const repoRoot = resolve(__dirname, "../..")
const appVersion = JSON.parse(readFileSync(resolve(webAppRoot, "package.json"), "utf8")).version as string

function gitBuildId(): string {
  try {
    const sha = execSync("git rev-parse --short HEAD", { encoding: "utf8", cwd: repoRoot }).trim()
    const dirty = execSync("git status --porcelain", { encoding: "utf8", cwd: repoRoot }).trim()
    return dirty ? `${sha}+` : sha
  } catch {
    return "dev"
  }
}

export default defineConfig({
  define: {
    __PRODUCT_NAME__: JSON.stringify(PRODUCT_NAME),
    __APP_VERSION__: JSON.stringify(appVersion),
    __APP_BUILD_ID__: JSON.stringify(gitBuildId()),
    __APP_BUILD_TIME__: JSON.stringify(new Date().toISOString()),
  },
  plugins: [
    vue(),
    tailwindcss(),
    // Single-source the product name into index.html (the {{PRODUCT_NAME}}
    // placeholder) so a rebrand only edits src/shared/brand.ts.
    { name: "brand-html", transformIndexHtml: (html) => html.replaceAll("{{PRODUCT_NAME}}", PRODUCT_NAME) },
    VitePWA({
      strategies: "injectManifest",
      srcDir: ".",
      filename: "sw.ts",
      registerType: "autoUpdate",
      injectRegister: "auto",
      includeAssets: ["icons/*"],
      manifest: {
        name: PRODUCT_NAME,
        short_name: PRODUCT_NAME,
        theme_color: "#0b0b0b",
        background_color: "#0b0b0b",
        display: "standalone",
        icons: [
          { src: "/icons/icon-192.png", sizes: "192x192", type: "image/png" },
          { src: "/icons/icon-512.png", sizes: "512x512", type: "image/png" },
          { src: "/icons/icon-mask.png", sizes: "512x512", type: "image/png", purpose: "maskable" },
        ],
      },
      injectManifest: {
        // The worker is push-only — no precache, so nothing to inject. A fetch
        // handler would put SW startup on every navigation's critical path
        // (the Android blank-screen root cause).
        injectionPoint: undefined,
      },
    }),
  ],
  resolve: {
    dedupe: [
      "@codemirror/autocomplete",
      "@codemirror/commands",
      "@codemirror/language",
      "@codemirror/lint",
      "@codemirror/lsp-client",
      "@codemirror/search",
      "@codemirror/state",
      "@codemirror/view",
      "codemirror",
    ],
    alias: {
      "@": resolve(__dirname, "src"),
      "@codemirror/lsp-client": resolve(__dirname, "node_modules/@codemirror/lsp-client"),
      "@novnc/novnc/core/rfb": resolve(__dirname, "node_modules/@novnc/novnc/core/rfb.js"),
    },
  },
  build: {
    outDir: "../channels/web/static",
    emptyOutDir: true,
    target: "es2022",
  },
  optimizeDeps: {
    esbuildOptions: {
      target: "es2022",
    },
  },
})
