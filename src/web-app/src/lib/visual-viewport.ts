/**
 * Publish the visual-viewport height into a CSS custom property (`--vvh`) so
 * full-height "fixed-shell" views can size themselves to the space ABOVE the
 * on-screen keyboard.
 *
 * Why: on installed iOS PWAs (and Android's default `resizes-visual` mode) the
 * soft keyboard OVERLAYS the layout viewport — `100dvh`/`100vh` stay full-height,
 * so a bottom-anchored input (the terminal cursor, the chat composer) ends up
 * hidden behind the keyboard. `window.visualViewport.height` is the one metric
 * that reflects the keyboard, so we mirror it into `--vvh`; shells use
 * `height: var(--vvh, 100dvh)` and shrink to fit when the keyboard opens. The
 * `100dvh` fallback covers first paint (before this runs) and old browsers with
 * no `visualViewport`.
 *
 * Call once at startup (main.ts). The listeners live for the app's lifetime.
 */
export function initVisualViewportVar(): void {
  const vv = window.visualViewport
  const root = document.documentElement
  if (!vv) {
    // No visualViewport (very old browser) → leave --vvh unset; shells fall back
    // to 100dvh. Nothing more we can do without the API.
    return
  }
  const update = () => {
    root.style.setProperty("--vvh", `${Math.round(vv.height)}px`)
  }
  update()
  // `resize` fires when the keyboard opens/closes (and on URL-bar show/hide);
  // `scroll` fires as iOS animates the keyboard and shifts the visual viewport —
  // the height can settle across a couple of these, so we track both.
  vv.addEventListener("resize", update)
  vv.addEventListener("scroll", update)
}
