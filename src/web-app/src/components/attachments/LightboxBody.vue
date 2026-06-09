<script setup lang="ts">
import { ref } from "vue"
import { Download } from "lucide-vue-next"
import { fileUrl } from "@/lib/fileUrl"

const props = defineProps<{ file_id: string; name?: string }>()
const failed = ref(false)
const src = fileUrl(props.file_id)
</script>

<template>
  <div class="relative max-w-full max-h-full p-6 flex flex-col items-center justify-center">
    <img
      v-if="!failed"
      :src="src"
      :alt="name ?? 'attachment'"
      class="max-w-full max-h-[80vh] object-contain"
      @error="failed = true"
    />
    <div v-else class="w-64 h-64 bg-white/10 animate-pulse rounded-md" />

    <a
      v-if="!failed"
      :href="src"
      :download="name ?? file_id"
      class="absolute bottom-8 right-8 p-2 rounded-full bg-white/10 text-white hover:bg-white/20"
      aria-label="Download"
    >
      <Download class="size-5" />
    </a>
  </div>
</template>
