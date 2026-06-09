<script setup lang="ts">
import { X } from "lucide-vue-next"
import { onMounted, onBeforeUnmount, watch } from "vue"
import { useLightbox } from "@/composables/useLightbox"
import LightboxBody from "./LightboxBody.vue"

const lightbox = useLightbox()

function onKeydown(e: KeyboardEvent) {
  if (e.key === "Escape") lightbox.close()
}

onMounted(() => {
  document.addEventListener("keydown", onKeydown)
})

onBeforeUnmount(() => {
  document.removeEventListener("keydown", onKeydown)
  document.body.style.overflow = ""
})

watch(() => lightbox.current.value, (val) => {
  document.body.style.overflow = val ? "hidden" : ""
})
</script>

<template>
  <div
    v-if="lightbox.current.value"
    class="fixed inset-0 z-50 bg-black/90 flex items-center justify-center"
    @click.self="lightbox.close()"
  >
    <LightboxBody
      :key="lightbox.current.value.file_id"
      :file_id="lightbox.current.value.file_id"
      :name="lightbox.current.value.name"
    />

    <button
      type="button"
      class="absolute top-4 right-4 p-2 rounded-full bg-white/10 text-white hover:bg-white/20"
      aria-label="Close"
      @click="lightbox.close()"
    >
      <X class="size-5" />
    </button>
  </div>
</template>
