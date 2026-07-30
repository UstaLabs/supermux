<script lang="ts" setup>
import type { ToasterProps } from 'vue-sonner'
import { computed } from 'vue'

import {
  CircleCheckIcon,
  InfoIcon,
  Loader2Icon,
  OctagonXIcon,
  TriangleAlertIcon,
  XIcon,
} from '@lucide/vue'
import { Toaster as Sonner } from 'vue-sonner'
import { cn } from '@/lib/utils'

const props = defineProps<ToasterProps>()

// toastOptions is bound explicitly below AND was being re-applied by v-bind="props",
// so the spread silently clobbered the rounded-2xl default. Spread everything except
// toastOptions, and merge the caller's on top of the default instead.
const rest = computed(() => {
  const { toastOptions: _toastOptions, ...others } = props
  return others
})
</script>

<template>
  <Sonner
    :class="cn('toaster group', props.class)"
    :style="{
      '--normal-bg': 'var(--popover)',
      '--normal-text': 'var(--popover-foreground)',
      '--normal-border': 'var(--border)',
      '--border-radius': 'var(--radius)',
    }"
    :toast-options="{
      ...{ classes: { toast: 'rounded-2xl' } },
      ...props.toastOptions,
    }"
    v-bind="rest"
  >
    <template #success-icon>
      <CircleCheckIcon class="size-4" />
    </template>
    <template #info-icon>
      <InfoIcon class="size-4" />
    </template>
    <template #warning-icon>
      <TriangleAlertIcon class="size-4" />
    </template>
    <template #error-icon>
      <OctagonXIcon class="size-4" />
    </template>
    <template #loading-icon>
      <div>
        <Loader2Icon class="size-4 animate-spin" />
      </div>
    </template>
    <template #close-icon>
      <XIcon class="size-4" />
    </template>
  </Sonner>
</template>
