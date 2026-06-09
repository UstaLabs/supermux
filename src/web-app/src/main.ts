import { patchLSPPluginGet } from "./lib/lsp-plugin"
import { createApp } from "vue"
import { createPinia } from "pinia"
import App from "./App.vue"
import { router } from "./router"
import { useWS } from "./api/ws"
import "vue-sonner/style.css"
import "./style.css"

patchLSPPluginGet()

if ("serviceWorker" in navigator) {
  const hadController = Boolean(navigator.serviceWorker.controller)
  let reloadingForUpdate = false
  navigator.serviceWorker.addEventListener("controllerchange", () => {
    if (!hadController || reloadingForUpdate) return
    reloadingForUpdate = true
    location.reload()
  })

  navigator.serviceWorker.addEventListener("message", (event) => {
    const data = event.data as { type?: string; to?: string } | null
    if (data?.type === "navigate" && typeof data.to === "string") {
      router.push(data.to)
    }
  })
}

const app = createApp(App).use(createPinia()).use(router)

// Surface uncaught frontend errors into the broker logs — the PWA runs on
// phones/tablets where the dev console isn't reachable, so this is how we see
// what actually broke in the field.
function reportClientError(kind: string, message: string, stack?: string) {
  try {
    useWS().send({ type: "client_error", kind, message, stack: stack?.slice(0, 2000), url: location.pathname })
  } catch {
    // ws not ready / store inactive — nothing we can do
  }
}
window.addEventListener("error", (e) => reportClientError("error", e.message, (e.error as Error | undefined)?.stack))
window.addEventListener("unhandledrejection", (e) => {
  const r = e.reason as { message?: string; stack?: string } | undefined
  reportClientError("unhandledrejection", String(r?.message ?? e.reason), r?.stack)
})
app.config.errorHandler = (err) => reportClientError("vue", String((err as Error)?.message ?? err), (err as Error)?.stack)

app.mount("#app")
