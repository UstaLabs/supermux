<script setup lang="ts">
import { ref } from "vue"
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogDescription } from "@/components/ui/dialog"
import { Button } from "@/components/ui/button"

const open = ref(true)
const value = ref("")
const error = ref<string | null>(null)

function tryParse() {
  error.value = null
  const raw = value.value.trim()
  if (!raw) {
    error.value = "Paste the pairing URL from your broker terminal."
    return
  }

  // accept a full URL (?t= or legacy #t=) or just the token alone
  let token: string | null = null
  const m = raw.match(/[#?&]t=([^&\s]+)/)
  if (m) token = decodeURIComponent(m[1]!)
  else if (/^[A-Za-z0-9_-]{20,}$/.test(raw)) token = raw

  if (!token) {
    error.value = "Didn't see a token. Paste the full URL (looks like https://…/pair?t=…) or just the token."
    return
  }

  // Navigate to the server pair endpoint: it sets the HttpOnly cookie and
  // redirects back in. The token never persists in JS.
  open.value = false
  window.location.href = `/pair?t=${encodeURIComponent(token)}`
}
</script>

<template>
  <Dialog v-model:open="open">
    <DialogContent
      :hide-close="true"
      class="sm:max-w-md"
      @interact-outside="(e: Event) => e.preventDefault()"
      @escape-key-down="(e: Event) => e.preventDefault()"
    >
      <DialogHeader>
        <DialogTitle>Pair this device</DialogTitle>
        <DialogDescription>
          Run <code class="text-primary">bun run pair &lt;name&gt;</code> on your broker host,
          then paste the URL here.
        </DialogDescription>
      </DialogHeader>
      <div class="space-y-3">
        <textarea
          v-model="value"
          placeholder="https://your-tunnel.example.com/pair?t=…"
          rows="3"
          class="w-full rounded-md bg-card border border-border px-3 py-2 text-sm font-mono break-all focus:outline-none focus:ring-1 focus:ring-primary"
        />
        <p v-if="error" class="text-xs text-red-400">{{ error }}</p>
        <Button class="w-full" @click="tryParse">Pair</Button>
        <p class="text-xs text-muted-foreground">
          Pairing stores a secure, HttpOnly cookie this browser can't read. On iOS,
          installing this app to your home screen uses storage isolated from your browser
          tab — that's why each needs pairing once.
        </p>
      </div>
    </DialogContent>
  </Dialog>
</template>
