<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from "vue"
import { Check, CheckCircle2, Copy, RefreshCw, Smartphone } from "lucide-vue-next"
import QrcodeVue from "qrcode.vue"
import { api } from "@/api/client"
import { Button } from "@/components/ui/button"

interface Device {
  name: string
  last_seen_at: string | null
}

const loading = ref(true)
const refreshing = ref(false)
const error = ref<string | null>(null)
const pairingUrl = ref<string | null>(null)
const pairingName = ref<string | null>(null)
const paired = ref(false)
const copiedAt = ref(0)
const copied = computed(() => copiedAt.value > 0 && Date.now() - copiedAt.value < 1500)

let pollTimer: number | undefined

function stopPolling() {
  if (pollTimer !== undefined) window.clearInterval(pollTimer)
  pollTimer = undefined
}

async function checkPairing() {
  if (!pairingName.value || paired.value) return
  try {
    const devices = await api.listDevices() as Device[]
    const device = devices.find((item) => item.name === pairingName.value)
    if (device?.last_seen_at) {
      paired.value = true
      stopPolling()
    }
  } catch {
    // A transient poll failure should not replace a usable pairing code.
  }
}

function startPolling() {
  stopPolling()
  void checkPairing()
  pollTimer = window.setInterval(checkPairing, 1_000)
}

async function revokePendingPairing() {
  const name = pairingName.value
  if (!name || paired.value) return
  try {
    const devices = await api.listDevices() as Device[]
    const device = devices.find((item) => item.name === name)
    if (device && !device.last_seen_at) await api.revokeDevice(name)
  } catch {
    // Best-effort cleanup; never revoke a device unless the broker confirms it is unused.
  }
}

async function generatePairing(refresh = false) {
  stopPolling()
  error.value = null
  if (refresh) {
    refreshing.value = true
    await revokePendingPairing()
    pairingUrl.value = null
    pairingName.value = null
    paired.value = false
    loading.value = true
  } else {
    loading.value = true
  }

  try {
    const result = await api.addDevice("phone") as { url: string; name: string }
    pairingUrl.value = result.url
    pairingName.value = result.name
    paired.value = false
    startPolling()
  } catch (cause: any) {
    error.value = cause?.message ?? "Couldn't create a phone pairing code."
  } finally {
    loading.value = false
    refreshing.value = false
  }
}

async function copyPairingUrl() {
  if (!pairingUrl.value) return
  try {
    await navigator.clipboard.writeText(pairingUrl.value)
    copiedAt.value = Date.now()
    window.setTimeout(() => { copiedAt.value = 0 }, 1_500)
  } catch {
    error.value = "Couldn't copy the pairing link."
  }
}

onMounted(() => generatePairing())
onBeforeUnmount(() => {
  stopPolling()
  void revokePendingPairing()
})
</script>

<template>
  <div class="flex flex-1 items-center justify-center px-5 py-8">
    <div class="flex w-full max-w-xl flex-col items-center text-center">
      <template v-if="paired">
        <div class="mb-5 grid size-20 place-items-center rounded-3xl bg-primary/10 ring-1 ring-primary/20">
          <CheckCircle2 class="size-11 text-primary" />
        </div>
        <h2 class="text-2xl font-bold tracking-tight">Your phone is connected</h2>
        <p class="mt-2 max-w-sm text-sm leading-relaxed text-muted-foreground">
          Supermux is paired and ready to use on your phone.
        </p>
        <Button variant="outline" class="mt-7" :disabled="refreshing" @click="generatePairing(true)">
          {{ refreshing ? "Preparing…" : "Connect another phone" }}
        </Button>
      </template>

      <template v-else>
        <div class="mb-5 grid size-16 place-items-center rounded-2xl bg-card ring-1 ring-border">
          <Smartphone class="size-8 text-primary" />
        </div>
        <h2 class="text-2xl font-bold tracking-tight">Connect from your phone</h2>
        <p class="mt-2 max-w-md text-sm leading-relaxed text-muted-foreground">
          Open Supermux on your iPhone, iPad, or Android device and scan this code.
        </p>

        <div v-if="loading" class="mt-9 flex h-[272px] items-center justify-center text-sm text-muted-foreground">
          Creating a secure pairing code…
        </div>

        <template v-else-if="pairingUrl">
          <div class="mt-7 rounded-3xl bg-white p-4 shadow-sm ring-1 ring-black/10" aria-label="Phone pairing QR code">
            <QrcodeVue
              :value="pairingUrl"
              :size="240"
              level="M"
              render-as="svg"
              background="#ffffff"
              foreground="#000000"
              :margin="1"
            />
          </div>

          <div class="mt-5 flex flex-wrap items-center justify-center gap-2">
            <Button variant="outline" size="sm" class="gap-2" @click="copyPairingUrl">
              <Check v-if="copied" class="size-4 text-primary" />
              <Copy v-else class="size-4" />
              {{ copied ? "Copied" : "Copy pairing link" }}
            </Button>
            <Button variant="ghost" size="sm" class="gap-2" :disabled="refreshing" @click="generatePairing(true)">
              <RefreshCw class="size-4" :class="refreshing ? 'animate-spin' : ''" />
              {{ refreshing ? "Refreshing…" : "Refresh code" }}
            </Button>
          </div>
          <p class="mt-4 max-w-sm text-xs leading-relaxed text-muted-foreground">
            The pairing link grants access to this host. Keep it private and only scan it with your device.
          </p>
        </template>

        <div v-if="error" class="mt-5 flex flex-col items-center gap-3">
          <p class="text-sm text-destructive">{{ error }}</p>
          <Button v-if="!pairingUrl" variant="outline" size="sm" @click="generatePairing()">Try again</Button>
        </div>
      </template>
    </div>
  </div>
</template>
