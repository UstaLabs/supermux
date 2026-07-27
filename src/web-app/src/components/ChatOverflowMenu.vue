<script setup lang="ts">
defineOptions({ name: "ChatOverflowMenu" })

import { computed } from "vue"
import { CheckIcon, MoreVertical } from "lucide-vue-next"
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuSub,
  DropdownMenuSubContent,
  DropdownMenuSubTrigger,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu"
import { useChatDetail } from "@/stores/chatDetail"
import { isChatDetailImplemented, type ChatDetailLevel } from "@/lib/chat-detail"

const props = defineProps<{
  /** Show "Continue in new conversation" (session is live). */
  canContinue?: boolean
}>()

const emit = defineEmits<{
  (e: "continue"): void
}>()

const chatDetail = useChatDetail()

const levelLabel = computed(() => chatDetail.levelLabel)

const levels: { id: ChatDetailLevel; title: string; desc: string; disabled?: boolean }[] = [
  { id: "low", title: "Low", desc: "Messages only · tools on status line" },
  { id: "medium", title: "Medium", desc: "Tool chips between messages" },
  { id: "high", title: "High", desc: "Full tool I/O & file edits — coming soon", disabled: true },
]

function selectLevel(level: ChatDetailLevel) {
  if (!isChatDetailImplemented(level)) return
  chatDetail.setLevel(level)
}
</script>

<template>
  <DropdownMenu>
    <DropdownMenuTrigger as-child>
      <button
        type="button"
        class="cmux-icon-button shrink-0"
        aria-label="Session menu"
        title="More"
      >
        <MoreVertical class="size-4" />
      </button>
    </DropdownMenuTrigger>
    <DropdownMenuContent align="end" class="min-w-[12rem] w-auto">
      <DropdownMenuItem
        v-if="props.canContinue"
        class="cursor-pointer"
        @click="emit('continue')"
      >
        Continue in new conversation
      </DropdownMenuItem>
      <DropdownMenuSeparator v-if="props.canContinue" />

      <DropdownMenuSub>
        <DropdownMenuSubTrigger class="cursor-default">
          <span class="flex-1">Detail</span>
          <span class="text-muted-foreground text-xs mr-0.5">{{ levelLabel }}</span>
        </DropdownMenuSubTrigger>
        <DropdownMenuSubContent class="min-w-[14rem] w-auto">
          <DropdownMenuItem
            v-for="opt in levels"
            :key="opt.id"
            class="cursor-pointer flex items-start gap-2 py-2"
            :disabled="opt.disabled"
            :class="opt.disabled ? 'opacity-55' : ''"
            @click="selectLevel(opt.id)"
          >
            <CheckIcon
              class="size-4 shrink-0 mt-0.5"
              :class="chatDetail.state.level === opt.id ? 'opacity-100 text-primary' : 'opacity-0'"
            />
            <div class="min-w-0 flex flex-col gap-0.5">
              <span class="font-medium leading-none">
                {{ opt.title }}
                <span
                  v-if="opt.disabled"
                  class="ml-1 text-[10px] font-medium text-muted-foreground bg-muted rounded-full px-1.5 py-0.5"
                >Soon</span>
              </span>
              <span class="text-xs text-muted-foreground leading-snug">{{ opt.desc }}</span>
            </div>
          </DropdownMenuItem>
        </DropdownMenuSubContent>
      </DropdownMenuSub>
    </DropdownMenuContent>
  </DropdownMenu>
</template>
