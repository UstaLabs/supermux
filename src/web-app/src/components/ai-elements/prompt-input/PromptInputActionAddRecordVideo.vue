<script setup lang="ts">
import { DropdownMenuItem } from "@/components/ui/dropdown-menu"
import { Video } from "lucide-vue-next"
import { ref } from "vue"
import { usePromptInput } from "./context"

const { addFiles } = usePromptInput()
const inputRef = ref<HTMLInputElement | null>(null)

function open() {
  inputRef.value?.click()
}

function onChange(e: Event) {
  const target = e.target as HTMLInputElement
  if (target.files?.length) addFiles(target.files)
  target.value = ""
}
</script>

<template>
  <DropdownMenuItem @select.prevent="open">
    <Video class="mr-2 size-4" />
    Record video
  </DropdownMenuItem>
  <input
    ref="inputRef"
    type="file"
    accept="video/*"
    capture="environment"
    class="hidden"
    @change="onChange"
  />
</template>
