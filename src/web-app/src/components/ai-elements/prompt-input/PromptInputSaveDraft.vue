<script setup lang="ts">
import type { HTMLAttributes } from 'vue'
import { InputGroupButton } from '@/components/ui/input-group'
import { cn } from '@/lib/utils'
import { usePromptInput } from './context'
import type { PromptInputMessage } from './types'

// Secondary "Save as draft" action. Lives inside the PromptInput subtree so it
// can inject the composer context and read the CURRENT text/files without going
// through submit (which clears the composer). Emits the current content as a
// PromptInputMessage — same shape the @submit handler receives — so the launcher
// can persist a draft with `@save-draft`.
const props = defineProps<{ class?: HTMLAttributes['class'], disabled?: boolean }>()

const emit = defineEmits<{
  (e: 'save-draft', payload: PromptInputMessage): void
}>()

const { textInput, files } = usePromptInput()

function onClick() {
  emit('save-draft', { text: textInput.value, files: [...files.value] })
}
</script>

<template>
  <InputGroupButton
    type="button"
    variant="outline"
    size="sm"
    :disabled="props.disabled"
    :class="cn('px-3', props.class)"
    @click="onClick"
  >
    <slot>Save as draft</slot>
  </InputGroupButton>
</template>
