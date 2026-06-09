<script setup lang="ts">
import { ref, computed } from "vue"
import { ArrowLeft, ArrowRight } from "lucide-vue-next"
import { ONBOARDING_STEP_LABELS, useOnboarding } from "@/stores/onboarding"
import { Button } from "@/components/ui/button"

import SetupStepWelcome from "./setup/SetupStepWelcome.vue"
import SetupStepAgents from "./setup/SetupStepAgents.vue"
import SetupStepConnectivity from "./setup/SetupStepConnectivity.vue"
import SetupStepDone from "./setup/SetupStepDone.vue"

const ob = useOnboarding()

// canProceed is gated by the Agents step (step 1)
const agentsCanProceed = ref(false)

const stepLabels = ONBOARDING_STEP_LABELS
const doneStep = stepLabels.length - 1

const nextDisabled = computed(() => {
  // Agents step (1) is gated
  if (ob.step === 1) return !agentsCanProceed.value
  // Done step has its own action button
  if (ob.step === doneStep) return true
  return false
})
</script>

<template>
  <div class="h-dvh overflow-hidden bg-background text-foreground flex flex-col">
    <!-- Header -->
    <header
      class="flex items-center justify-between px-3 py-3 border-b border-border sticky top-0 bg-background/95 backdrop-blur z-10"
      style="padding-top: calc(env(safe-area-inset-top, 0px) + 0.75rem)"
    >
      <div class="flex items-center gap-2 min-w-0">
        <h1 class="text-base font-semibold tracking-tight">Setup</h1>
      </div>
      <span class="text-xs text-muted-foreground tabular-nums">
        Step {{ ob.step + 1 }} of {{ stepLabels.length }}
        <span class="ml-1 text-foreground font-medium">— {{ stepLabels[ob.step] }}</span>
      </span>
    </header>

    <!-- Step progress bar -->
    <div class="h-0.5 bg-border">
      <div
        class="h-full bg-primary transition-all duration-300"
        :style="{ width: `${((ob.step + 1) / stepLabels.length) * 100}%` }"
      />
    </div>

    <!-- Step content -->
    <div class="flex-1 flex flex-col overflow-y-auto">
      <SetupStepWelcome v-if="ob.step === 0" />
      <SetupStepAgents
        v-else-if="ob.step === 1"
        @update:canProceed="(v) => (agentsCanProceed = v)"
      />
      <SetupStepConnectivity v-else-if="ob.step === 2" />
      <SetupStepDone v-else-if="ob.step === 3" />
    </div>

    <!-- Footer nav -->
    <footer
      v-if="ob.step < doneStep"
      class="flex items-center gap-3 px-4 py-4 border-t border-border"
      :class="ob.step === 0 ? 'justify-center' : 'justify-between'"
      style="padding-bottom: calc(env(safe-area-inset-bottom, 0px) + 1rem)"
    >
      <!-- Back button (hidden on step 0) -->
      <Button
        v-if="ob.step > 0"
        variant="ghost"
        size="sm"
        class="gap-1.5"
        @click="ob.prev()"
      >
        <ArrowLeft class="size-4" />
        Back
      </Button>

      <!-- Start (welcome) / Next -->
      <Button
        size="sm"
        class="gap-1.5"
        :disabled="nextDisabled"
        @click="ob.next()"
      >
        {{ ob.step === 0 ? "Start" : "Next" }}
        <ArrowRight v-if="ob.step > 0" class="size-4" />
      </Button>
    </footer>
  </div>
</template>
