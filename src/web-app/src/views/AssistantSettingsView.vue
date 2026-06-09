<script setup lang="ts">
import { useRouter } from "vue-router"
import { ArrowLeft } from "lucide-vue-next"
import SetupStepIdentity from "@/views/setup/SetupStepIdentity.vue"

const router = useRouter()

function goBack() {
  if (window.history.length > 1) router.back()
  else router.push("/settings")
}
</script>

<template>
  <div class="min-h-screen bg-background text-foreground">
    <header
      class="flex items-center gap-2 px-3 py-3 border-b border-border sticky top-0 bg-background/95 backdrop-blur z-10"
      style="padding-top: calc(env(safe-area-inset-top, 0px) + 0.75rem)"
    >
      <button class="text-muted-foreground hover:text-foreground transition -ml-1 p-1" aria-label="Back" @click="goBack">
        <ArrowLeft class="size-5" />
      </button>
      <h1 class="text-base font-semibold tracking-tight">Assistant</h1>
    </header>

    <SetupStepIdentity />
  </div>
</template>
