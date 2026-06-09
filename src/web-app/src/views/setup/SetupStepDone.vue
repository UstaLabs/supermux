<script setup lang="ts">
import { ref, onMounted } from "vue"
import { useRouter } from "vue-router"
import { PartyPopper } from "lucide-vue-next"
import { api } from "@/api/client"
import { Button } from "@/components/ui/button"
import { toast } from "vue-sonner"
import { useOnboarding } from "../../stores/onboarding"
import { useSessions } from "../../stores/sessions"

const router = useRouter()
const finishing = ref(false)
const paName = ref("your assistant")

const onboarding = useOnboarding()
const sessions = useSessions()

type SetupSession = Awaited<ReturnType<typeof api.listSessions>>[number]

function findPersonalAssistantSession(live: SetupSession[], label: string): SetupSession | undefined {
  return (
    live.find((s) => s.role === "personal_assistant" && s.isDefault) ??
    live.find((s) => s.role === "personal_assistant") ??
    live.find((s) => s.name === label) ??
    live.find((s) => s.name === "assistant")
  )
}

onMounted(async () => {
  try {
    const cfg = await api.getAppConfig()
    paName.value = cfg.paName || "your assistant"
  } catch {}
})

async function markDone(): Promise<void> {
  // Flip onboarded (server-side + local store so the router guard won't bounce).
  // This is also what triggers the broker to spawn the PA (named paName, with the
  // agent auth just set), since auto-spawn is deferred until onboarding completes.
  await api.saveAppConfig({ onboarded: true })
  onboarding.setOnboarded(true)
}

async function startTalking() {
  finishing.value = true
  try {
    await markDone()
    // The PA spawns asynchronously on completion. Poll the server directly —
    // the WS-backed sessions store isn't synced yet during the setup→app
    // transition (that's why the list looked empty until a manual refresh) —
    // and seed the store from the fetch so "/" isn't blank either. Then drop
    // into the PA's chat. Cold Docker boots can take ~15s, so wait up to ~30s.
    let id: string | undefined
    for (let i = 0; i < 60 && !id; i++) {
      try {
        const live = await api.listSessions()
        sessions.replace(live as any)
        id = findPersonalAssistantSession(live, paName.value)?.id
      } catch {}
      if (!id) await new Promise((r) => setTimeout(r, 500))
    }
    router.push(id ? `/s/${id}` : "/")
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
        supermux is ready — jump into a chat with {{ paName }}.
      </p>
    </div>

    <Button class="w-full max-w-xs" :disabled="finishing" @click="startTalking">
      <span
        v-if="finishing"
        class="size-4 border-2 border-foreground/30 border-t-foreground rounded-full animate-spin mr-2"
      />
      Start talking to {{ paName }}
    </Button>
  </div>
</template>
