<script setup lang="ts">
defineOptions({ name: "ChatComposer" })

// The chat composer body, rendered INSIDE <PromptInput> so it can read the
// composer context (focus / draft / attachments / busy). On mobile it morphs
// between a slim floating pill (rest) and the full card (active) — parity with
// the iOS composer (apps/iosApp/Supermux/Chat/ChatPane.swift). On desktop it
// renders the unchanged full card (block-end addons stacked by InputGroup).
import { computed, ref } from "vue"
import { cn } from "@/lib/utils"
import { useIsDesktop } from "@/composables/useIsDesktop"
import { usePromptInput } from "@/components/ai-elements/prompt-input/context"
import {
  PromptInputHeader,
  PromptInputBody,
  PromptInputFooter,
  PromptInputTools,
  PromptInputTextarea,
  PromptInputSubmit,
  PromptInputAttachments,
} from "@/components/ai-elements/prompt-input"
import ComposerAttachMenu from "@/components/ComposerAttachMenu.vue"
import ModelPill from "@/components/ModelPill.vue"
import EffortPill from "@/components/EffortPill.vue"
import MicButton from "@/components/voice/MicButton.vue"
import VoiceRecorder from "@/components/voice/VoiceRecorder.vue"

const props = defineProps<{
  sessionId: string
  /** WS connected — gates the pills / mic / submit. */
  connected: boolean
  /** An attachment is still uploading/failed — blocks send + mic. */
  hasPendingUploads: boolean
}>()

const emit = defineEmits<{ (e: "open-model"): void; (e: "open-effort"): void }>()

const isDesktop = useIsDesktop()
const { focused, textInput, files, isLoading } = usePromptInput()

// Voice recording takes over the input row (composer-local, like iOS).
const isRecording = ref(false)
function startRecording() { isRecording.value = true }
function onRecordingDone() { isRecording.value = false }

// Expanded === iOS `composerExpanded` (composing || hasContent || isBusy).
// Desktop is always expanded so it keeps today's full card.
const expanded = computed(() =>
  isDesktop.value
  || focused.value
  || textInput.value.length > 0
  || files.value.length > 0
  || isLoading.value
  || isRecording.value,
)

// Mic + submit share the same gate; the pills only need a live connection.
const actionsDisabled = computed(() => !props.connected || props.hasPendingUploads || isRecording.value)

const SUBMIT_CLASS =
  "shrink-0 rounded-full size-9 bg-primary text-primary-foreground hover:bg-primary/90 disabled:bg-muted disabled:text-muted-foreground disabled:opacity-100"
const ROUND_BTN = "size-8 shrink-0 rounded-full text-muted-foreground"

// Floating glass shell (mobile). Morphs radius + padding between the resting
// pill and the expanded card; height animates as the footer/attachments reveal.
const cardClass = computed(() =>
  cn(
    "flex flex-col border backdrop-blur-xl transition-all duration-200 ease-out",
    expanded.value
      ? "gap-2 rounded-3xl border-border/60 bg-card/80 px-3 py-2.5 shadow-lg"
      : "gap-0 rounded-full border-border/60 bg-card/80 pl-2.5 pr-2 py-1 shadow-sm",
  ),
)
</script>

<template>
  <!-- DESKTOP: unchanged full card (InputGroup stacks these block-end addons). -->
  <template v-if="isDesktop">
    <PromptInputHeader>
      <PromptInputAttachments />
    </PromptInputHeader>
    <PromptInputBody>
      <VoiceRecorder v-if="isRecording" :session-id="sessionId" @done="onRecordingDone" />
      <PromptInputTextarea v-else placeholder="Message…" />
    </PromptInputBody>
    <PromptInputFooter>
      <PromptInputTools>
        <ModelPill :session-id="sessionId" :disabled="!connected" @click="emit('open-model')" />
        <EffortPill :session-id="sessionId" :disabled="!connected" @click="emit('open-effort')" />
        <ComposerAttachMenu />
        <MicButton :disabled="actionsDisabled" @start="startRecording" />
      </PromptInputTools>
      <PromptInputTools class="ml-auto">
        <PromptInputSubmit :disabled="actionsDisabled" :class="SUBMIT_CLASS" />
      </PromptInputTools>
    </PromptInputFooter>
  </template>

  <!-- MOBILE: floating pill that morphs into the card. -->
  <div v-else :class="cardClass">
    <!-- Staged attachments (expanded only; self-hides when empty). -->
    <PromptInputAttachments v-if="expanded" class="px-1 pt-1" />

    <!-- Recording replaces the whole input row. -->
    <VoiceRecorder v-if="isRecording" :session-id="sessionId" @done="onRecordingDone" />

    <template v-else>
      <!-- Main row: ALWAYS holds the single textarea node, so focus survives the
           morph. The inline +/mic show only while compact. -->
      <div class="flex items-center gap-1.5">
        <ComposerAttachMenu v-if="!expanded" :trigger-class="ROUND_BTN" />
        <PromptInputTextarea
          placeholder="Message…"
          :class="expanded ? 'min-h-[2.5rem] max-h-44 py-2' : 'min-h-0 max-h-[2.25rem] py-1.5'"
        />
        <MicButton v-if="!expanded" class="shrink-0" :disabled="actionsDisabled" @start="startRecording" />
      </div>

      <!-- Footer: revealed on expand — +, mic, model, effort, send. -->
      <div v-if="expanded" class="flex items-center gap-1.5 px-1">
        <ComposerAttachMenu :trigger-class="ROUND_BTN" />
        <MicButton class="shrink-0" :disabled="actionsDisabled" @start="startRecording" />
        <ModelPill :session-id="sessionId" :disabled="!connected" @click="emit('open-model')" />
        <EffortPill :session-id="sessionId" :disabled="!connected" @click="emit('open-effort')" />
        <PromptInputSubmit :disabled="actionsDisabled" :class="cn('ml-auto', SUBMIT_CLASS)" />
      </div>
    </template>
  </div>
</template>
