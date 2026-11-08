// What every shown page gets before its own code (spec, "The page API"): the theme as --sm-* variables (#91's
// palette, from apps/shared Theme.kt), a base sheet, and window.supermux. The evals inject exactly this.
const DARK = {
  bg: "oklch(0.14 0.006 130)", fg: "oklch(0.94 0.008 120)", card: "oklch(0.18 0.008 130)",
  primary: "oklch(0.72 0.105 180)", "on-primary": "oklch(0.13 0.018 170)", "primary-container": "oklch(0.27 0.03 175)",
  muted: "oklch(0.24 0.008 130)", "muted-fg": "oklch(0.68 0.018 125)", border: "oklch(0.28 0.01 130)",
  "outline-var": "oklch(0.21 0.008 130)", code: "oklch(0.115 0.008 130)",
  destructive: "oklch(0.72 0.18 24)", warning: "oklch(0.78 0.12 75)",
  "chart-1": "oklch(0.72 0.105 180)", "chart-2": "oklch(0.78 0.12 75)", "chart-3": "oklch(0.72 0.18 24)",
  "chart-4": "oklch(0.70 0.12 250)", "chart-5": "oklch(0.70 0.12 300)",
}
const LIGHT = {
  bg: "oklch(0.955 0.006 115)", fg: "oklch(0.19 0.008 130)", card: "oklch(0.995 0.004 105)",
  primary: "oklch(0.49 0.105 185)", "on-primary": "oklch(0.985 0.01 180)", "primary-container": "oklch(0.90 0.03 180)",
  muted: "oklch(0.91 0.008 115)", "muted-fg": "oklch(0.51 0.014 130)", border: "oklch(0.86 0.01 110)",
  "outline-var": "oklch(0.91 0.008 112)", code: "oklch(0.995 0.004 105)",
  destructive: "oklch(0.54 0.19 27)", warning: "oklch(0.66 0.13 75)",
  "chart-1": "oklch(0.49 0.105 185)", "chart-2": "oklch(0.66 0.13 75)", "chart-3": "oklch(0.54 0.19 27)",
  "chart-4": "oklch(0.50 0.13 250)", "chart-5": "oklch(0.50 0.13 300)",
}

export const THEME_VARIABLES = Object.keys(DARK).map((k) => `--sm-${k}`)

/**
 * `nonce` names this document to the host: the shim sends it up with its end of the private channel, so a document
 * the page navigated to on its own (which has no shim and doesn't know the nonce) can never open a channel.
 */
export function pagePrelude(theme: "dark" | "light", nonce: string): string {
  const vars = Object.entries(theme === "dark" ? DARK : LIGHT).map(([k, v]) => `--sm-${k}:${v}`).join(";")
  return `<style id="sm-base">:root{color-scheme:${theme};${vars};--sm-radius:8px;` +
    `--sm-font:"Geist",system-ui,-apple-system,"Segoe UI",sans-serif;--sm-mono:"Geist Mono",ui-monospace,monospace}` +
    `html,body{margin:0;background:transparent;color:var(--sm-fg);font:14px/1.45 var(--sm-font)}</style>` +
    `<script id="sm-api">(() => {
  // Everything the shim touches later is captured now, before any page script runs: a page can redefine
  // prototype getters and setters, but not these references.
  const apply = Reflect.apply, now = Date.now;
  const getter = (proto, key) => Object.getOwnPropertyDescriptor(proto, key).get;
  const dataOf = getter(MessageEvent.prototype, "data"), targetOf = getter(Event.prototype, "target");
  const nodeTypeOf = getter(Node.prototype, "nodeType"), closest = Element.prototype.closest;
  const portPost = MessagePort.prototype.postMessage;
  const uaGet = Object.getOwnPropertyDescriptor(Navigator.prototype, "userActivation")?.get;
  const activeGet = typeof UserActivation === "function" ? getter(UserActivation.prototype, "isActive") : undefined;
  const activated = () => !uaGet || !activeGet || apply(activeGet, apply(uaGet, navigator, []), []);
  // The private channel to the host, made here; the page never sees either end.
  const channel = new MessageChannel(), port = channel.port1;
  parent.postMessage({ sm: "hello", nonce: ${JSON.stringify(nonce)} }, "*", [channel.port2]);
  const waiting = new Map();
  port.onmessage = (m) => {
    const d = apply(dataOf, m, []);
    const done = d && waiting.get(d.id);
    if (done) { waiting.delete(d.id); done(d.ok ? { ok: true } : { ok: false, reason: String(d.reason || "refused") }); }
  };
  // A send is armed only by a click the browser made (a tap, a mouse click, Enter or Space, a screen reader's
  // activation) on a real button: never by a scroll, a drag, a key, or a page's own el.click() or dispatchEvent.
  const BUTTON = 'button, input[type="button"], input[type="submit"]';
  let clickAt = 0, armed = false;
  addEventListener("click", (e) => {
    if (!e.isTrusted) return;
    try {
      const t = apply(targetOf, e, []);
      if (apply(nodeTypeOf, t, []) === 1 && apply(closest, t, [BUTTON])) { clickAt = now(); armed = true; }
    } catch {}
  }, { capture: true });
  let n = 0;
  Object.defineProperty(window, "supermux", { value: Object.freeze({
    theme: ${JSON.stringify(theme)},
    send(text) {
      const id = ++n;
      text = String(text).slice(0, 2000);
      const reason = !text ? "empty" : !armed ? "no click on a button" : now() - clickAt > 1000 ? "too late after the click"
        : !activated() ? "no user activation" : "";
      if (reason) { apply(portPost, port, [{ sm: "refused", id, text, reason }]); return Promise.resolve({ ok: false, reason }); }
      armed = false;
      return new Promise((resolve) => { waiting.set(id, resolve); apply(portPost, port, [{ sm: "send", id, text, clickAt }]); });
    },
  }) });
  document.documentElement.dataset.theme = ${JSON.stringify(theme)};
  document.currentScript?.remove();
})();</script>`
}
