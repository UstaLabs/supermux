<script setup lang="ts">
import { ref, watch } from "vue"
import { DialogRoot, DialogPortal, DialogOverlay, DialogContent } from "reka-ui"
import { useIsDesktop } from "@/composables/useIsDesktop"
import { useSessions } from "@/stores/sessions"
import { api } from "@/api/client"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { toast } from "vue-sonner"

const props = defineProps<{ open: boolean }>()
const emit = defineEmits<{
  (e: "update:open", v: boolean): void
  (e: "created"): void
}>()

const isDesktop = useIsDesktop()
const sessions = useSessions()

const sessionName = ref("")
const port = ref<number | "">("")
const domain = ref("")
const submitting = ref(false)

watch(() => props.open, (isOpen) => {
  if (isOpen) {
    sessionName.value = sessions.list[0]?.name ?? ""
    port.value = ""
    domain.value = ""
  }
})

async function submit() {
  if (!sessionName.value) {
    toast.error("Select a session")
    return
  }
  if (!port.value || Number(port.value) <= 0) {
    toast.error("Enter a valid port number")
    return
  }
  submitting.value = true
  try {
    await api.createProxy({
      sessionName: sessionName.value,
      port: Number(port.value),
      domain: domain.value.trim() || undefined,
    })
    emit("created")
    emit("update:open", false)
  } catch (err: any) {
    toast.error(err?.message ?? "Failed to create proxy")
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <DialogRoot :open="props.open" @update:open="(v) => emit('update:open', v)">
    <DialogPortal>
      <DialogOverlay class="fixed inset-0 bg-black/50 z-50 data-open:animate-in data-closed:animate-out data-closed:fade-out-0 data-open:fade-in-0" />

      <!-- Desktop: centered dialog -->
      <DialogContent
        v-if="isDesktop"
        class="fixed top-1/2 left-1/2 z-50 -translate-x-1/2 -translate-y-1/2 w-[calc(100%-2rem)] max-w-[440px] bg-popover text-popover-foreground rounded-xl p-5 ring-1 ring-foreground/10 outline-none data-open:animate-in data-closed:animate-out data-closed:fade-out-0 data-open:fade-in-0 data-closed:zoom-out-95 data-open:zoom-in-95"
        @pointer-down-outside="emit('update:open', false)"
      >
        <h3 class="font-semibold text-base mb-4">New Proxy</h3>

        <div class="space-y-4">
          <div>
            <label class="text-xs text-muted-foreground font-medium mb-1 block">Session</label>
            <select
              v-model="sessionName"
              class="w-full h-9 rounded-md border border-input bg-background px-3 py-1 text-sm shadow-sm transition-colors focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring"
            >
              <option v-if="sessions.list.length === 0" value="" disabled>No sessions available</option>
              <option v-for="s in sessions.list" :key="s.name" :value="s.name">{{ s.name }}</option>
            </select>
          </div>

          <div>
            <label class="text-xs text-muted-foreground font-medium mb-1 block">Port</label>
            <Input v-model="port" type="number" placeholder="e.g. 3000" min="1" max="65535" />
          </div>

          <div>
            <label class="text-xs text-muted-foreground font-medium mb-1 block">Domain (optional)</label>
            <Input v-model="domain" placeholder="Leave empty for random" />
          </div>
        </div>

        <div class="flex gap-3 mt-5 justify-end">
          <Button variant="outline" size="sm" @click="emit('update:open', false)">Cancel</Button>
          <Button size="sm" :disabled="submitting" @click="submit">
            <span v-if="submitting" class="size-4 border-2 border-primary-foreground/30 border-t-primary-foreground rounded-full animate-spin mr-2" />
            Create Proxy
          </Button>
        </div>
      </DialogContent>

      <!-- Mobile: bottom sheet -->
      <DialogContent
        v-else
        class="fixed bottom-0 left-0 right-0 z-50 bg-popover text-popover-foreground rounded-t-2xl p-0 max-h-[85dvh] flex flex-col outline-none data-open:animate-in data-closed:animate-out data-open:slide-in-from-bottom data-closed:slide-out-to-bottom duration-200"
        @pointer-down-outside="emit('update:open', false)"
      >
        <div class="flex justify-center pt-3 pb-1">
          <div class="w-10 h-1 rounded-full bg-muted-foreground/30" />
        </div>
        <div class="px-4 pb-2">
          <h3 class="font-semibold text-base">New Proxy</h3>
        </div>

        <div class="overflow-y-auto flex-1 px-4 pb-[calc(env(safe-area-inset-bottom,0px)+1rem)] space-y-4">
          <div>
            <label class="text-xs text-muted-foreground font-medium mb-1 block">Session</label>
            <select
              v-model="sessionName"
              class="w-full h-10 rounded-md border border-input bg-background px-3 py-2 text-sm shadow-sm transition-colors focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring"
            >
              <option v-if="sessions.list.length === 0" value="" disabled>No sessions available</option>
              <option v-for="s in sessions.list" :key="s.name" :value="s.name">{{ s.name }}</option>
            </select>
          </div>

          <div>
            <label class="text-xs text-muted-foreground font-medium mb-1 block">Port</label>
            <Input v-model="port" type="number" placeholder="e.g. 3000" min="1" max="65535" />
          </div>

          <div>
            <label class="text-xs text-muted-foreground font-medium mb-1 block">Domain (optional)</label>
            <Input v-model="domain" placeholder="Leave empty for random" />
          </div>

          <Button class="w-full" :disabled="submitting" @click="submit">
            <span v-if="submitting" class="size-4 border-2 border-primary-foreground/30 border-t-primary-foreground rounded-full animate-spin mr-2" />
            Create Proxy
          </Button>
        </div>
      </DialogContent>
    </DialogPortal>
  </DialogRoot>
</template>
