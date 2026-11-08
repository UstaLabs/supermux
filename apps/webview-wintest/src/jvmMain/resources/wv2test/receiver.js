// Installed with AddScriptToExecuteOnDocumentCreated, so it runs in every document before the page's own scripts: the
// WebView2 delivery rule in WebEngine.kt. The host reaches the page only by executing a call to this receiver, which
// the page cannot replace. The test page registers its API once.
(() => {
  if (window !== window.top) return;
  const apply = Reflect.apply;
  let api = null;
  const receiver = Object.freeze({
    register(target) {
      if (api === null) api = target;
    },
    call(name, args) {
      if (api === null || typeof api[name] !== 'function') return { error: 'no page api ' + name };
      return { value: apply(api[name], api, args || []) };
    },
  });
  Object.defineProperty(window, '__wv2test', { value: receiver, writable: false, configurable: false, enumerable: false });
})();
