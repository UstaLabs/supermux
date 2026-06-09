<script setup lang="ts">
import type { HTMLAttributes } from 'vue'
import { cn } from '@/lib/utils'

interface Props {
  class?: HTMLAttributes['class']
}

const props = defineProps<Props>()
</script>

<template>
  <div
    :class="
      cn(
        'flex w-full flex-col gap-2 overflow-hidden text-sm',
        'group-[.is-user]:rounded-lg group-[.is-user]:border group-[.is-user]:border-border group-[.is-user]:bg-card/40 group-[.is-user]:px-3 group-[.is-user]:py-2.5 group-[.is-user]:text-foreground',
        'group-[.is-assistant]:text-foreground',
        props.class,
      )
    "
    v-bind="$attrs"
  >
    <slot />
  </div>
</template>
