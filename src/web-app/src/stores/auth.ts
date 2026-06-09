import { defineStore } from "pinia"
import { ref } from "vue"

// Cookie-only auth: the credential lives in an HttpOnly cmux_token cookie that
// JS cannot read. We only track whether we're paired (via GET /me) and expose a
// logout (POST /logout clears the cookie server-side). Pairing happens by
// navigating to /pair?t=<token>, which sets the cookie and redirects in.
export const useAuth = defineStore("auth", () => {
  const paired = ref(false)

  async function refresh(): Promise<boolean> {
    try {
      const res = await fetch("/me", { credentials: "same-origin" })
      paired.value = res.ok
    } catch {
      paired.value = false
    }
    return paired.value
  }

  async function logout(): Promise<void> {
    try {
      await fetch("/logout", { method: "POST", credentials: "same-origin" })
    } catch {}
    paired.value = false
  }

  return { paired, refresh, logout }
})
