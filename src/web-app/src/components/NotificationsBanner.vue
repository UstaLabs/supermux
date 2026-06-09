<script setup lang="ts">
import { Bell, X } from "lucide-vue-next"
import { useNotifications } from "@/composables/useNotifications"
import { toast } from "vue-sonner"

const { status, bannerDismissed, enable, dismissBanner } = useNotifications()

async function onEnable() {
  try {
    await enable()
    toast.success("Notifications enabled")
  } catch (err: any) {
    toast.error("Couldn't enable notifications", { description: err?.message ?? String(err) })
  }
}
</script>

<template>
  <div
    v-if="status === 'not-subscribed' && !bannerDismissed"
    class="px-3 py-2 bg-accent/40 border-b border-border flex items-center gap-3"
  >
    <Bell class="size-4 text-muted-foreground shrink-0" />
    <div class="flex-1 text-sm min-w-0">Get notified when a session replies</div>
    <button
      type="button"
      class="px-3 py-1 rounded-md bg-foreground text-background text-xs font-medium shrink-0"
      @click="onEnable"
    >Enable</button>
    <button
      type="button"
      class="size-7 rounded-full text-muted-foreground hover:bg-muted hover:text-foreground transition flex items-center justify-center shrink-0"
      aria-label="Dismiss notifications banner"
      @click="dismissBanner"
    >
      <X class="size-3" />
    </button>
  </div>
</template>
