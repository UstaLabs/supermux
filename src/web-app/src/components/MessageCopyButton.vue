<script setup lang="ts">
defineOptions({ name: "MessageCopyButton" })

import { onBeforeUnmount, ref } from "vue"
import { Check, Copy } from "lucide-vue-next"
import { toast } from "vue-sonner"
import { cn } from "@/lib/utils"

const props = defineProps<{ text: string; class?: string }>()

const copied = ref(false)
let timer: ReturnType<typeof setTimeout> | undefined

async function copy() {
  try {
    await navigator.clipboard.writeText(props.text)
    copied.value = true
    clearTimeout(timer)
    timer = setTimeout(() => { copied.value = false }, 1500)
  } catch {
    toast.error("Couldn't copy to clipboard")
  }
}

onBeforeUnmount(() => clearTimeout(timer))
</script>

<template>
  <button
    type="button"
    :class="cn(
      'inline-flex items-center gap-1 self-start rounded-md px-1.5 py-0.5',
      'text-[11px] text-muted-foreground/70 transition-colors',
      'hover:bg-muted/60 hover:text-foreground active:scale-95',
      props.class,
    )"
    :aria-label="copied ? 'Copied' : 'Copy message'"
    @click="copy"
  >
    <Check v-if="copied" class="size-3.5 text-emerald-500" />
    <Copy v-else class="size-3.5" />
    <span>{{ copied ? "Copied" : "Copy" }}</span>
  </button>
</template>
