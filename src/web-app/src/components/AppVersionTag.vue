<script setup lang="ts">
import { ref, watch } from "vue"
import { api } from "@/api/client"
import { useAuth } from "@/stores/auth"

const auth = useAuth()
const version = ref<string | null>(null)
const commit = ref<string | null>(null)
const buildId = __APP_BUILD_ID__
const buildTime = __APP_BUILD_TIME__

watch(
  () => auth.paired,
  async (paired) => {
    if (!paired) {
      version.value = null
      commit.value = null
      return
    }
    try {
      const status = await api.getUpdateStatus()
      if (!auth.paired) return
      version.value = status.current
      commit.value = status.commit
    } catch {
      // Keep the non-critical tag hidden when build metadata is unavailable.
    }
  },
  { immediate: true },
)
</script>

<template>
  <p
    v-if="version"
    class="text-[10px] leading-tight text-muted-foreground font-mono truncate"
    :title="`v${version} · ${commit ?? 'unknown'} · web ${buildId} · ${buildTime}`"
  >
    v{{ version }}
  </p>
</template>
