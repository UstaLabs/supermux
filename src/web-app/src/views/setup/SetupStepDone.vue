<script setup lang="ts">
import { ref } from "vue"
import { useRouter } from "vue-router"
import { PartyPopper } from "lucide-vue-next"
import { api } from "@/api/client"
import { Button } from "@/components/ui/button"
import { toast } from "vue-sonner"
import { useOnboarding } from "../../stores/onboarding"

const router = useRouter()
const finishing = ref(false)

const onboarding = useOnboarding()

async function markDone(): Promise<void> {
  // Flip onboarded server-side and locally so the router guard won't bounce.
  await api.saveAppConfig({ onboarded: true })
  onboarding.setOnboarded(true)
}

async function createFirstSession() {
  finishing.value = true
  try {
    await markDone()
    router.push("/new")
  } catch (e: any) {
    toast.error(e?.message ?? "Failed to finish setup")
  } finally {
    finishing.value = false
  }
}
</script>

<template>
  <div class="flex flex-col items-center justify-center flex-1 px-6 py-12 text-center gap-6">
    <div class="mx-auto size-16 rounded-2xl bg-emerald-500/10 ring-1 ring-emerald-500/20 flex items-center justify-center mb-2">
      <PartyPopper class="size-8 text-emerald-400" />
    </div>

    <div>
      <h2 class="text-2xl font-bold tracking-tight mb-3">You're all set!</h2>
      <p class="text-sm text-muted-foreground max-w-sm leading-relaxed">
        supermux is ready — create a session for the project you want to work on.
      </p>
    </div>

    <Button class="w-full max-w-xs" :disabled="finishing" @click="createFirstSession">
      <span
        v-if="finishing"
        class="size-4 border-2 border-foreground/30 border-t-foreground rounded-full animate-spin mr-2"
      />
      Create your first session
    </Button>
  </div>
</template>
