<script setup lang="ts">
import { computed } from "vue"
import claudeLogoUrl from "@/assets/agents/claude.svg"
import codexLogoUrl from "@/assets/agents/codex.svg"
import codexDarkLogoUrl from "@/assets/agents/codex-dark.svg"
import cursorLogoUrl from "@/assets/agents/cursor.svg"
import cursorDarkLogoUrl from "@/assets/agents/cursor-dark.svg"
import opencodeLogoUrl from "@/assets/agents/opencode.svg"
import opencodeDarkLogoUrl from "@/assets/agents/opencode-dark.svg"
import grokLogoUrl from "@/assets/agents/grok.svg"
import grokDarkLogoUrl from "@/assets/agents/grok-dark.svg"

const props = defineProps<{
  agent?: string
  /** When false, keep the default (dark) artwork — e.g. chat-list avatars on a white tile. */
  invertOnDark?: boolean
}>()

const logo = computed(() => {
  switch (props.agent?.toLowerCase()) {
    case "claude":
      return { src: claudeLogoUrl, alt: "Claude", darkSrc: null as string | null }
    case "codex":
      return { src: codexLogoUrl, darkSrc: codexDarkLogoUrl, alt: "Codex" }
    case "cursor":
      return { src: cursorLogoUrl, darkSrc: cursorDarkLogoUrl, alt: "Cursor" }
    case "opencode":
      return { src: opencodeLogoUrl, darkSrc: opencodeDarkLogoUrl, alt: "opencode" }
    case "grok":
      return { src: grokLogoUrl, darkSrc: grokDarkLogoUrl, alt: "Grok" }
    default:
      return null
  }
})

const swapOnDark = computed(
  () => props.invertOnDark !== false && logo.value?.darkSrc != null,
)
</script>

<template>
  <span v-if="logo" class="inline-flex shrink-0" v-bind="$attrs">
    <img
      :src="logo.src"
      :alt="logo.alt"
      :class="[
        'size-full object-contain',
        swapOnDark ? 'dark:hidden' : null,
      ]"
      draggable="false"
    />
    <img
      v-if="swapOnDark"
      :src="logo.darkSrc!"
      :alt="logo.alt"
      class="hidden size-full object-contain dark:block"
      draggable="false"
    />
  </span>
</template>
