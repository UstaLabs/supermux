<script setup lang="ts">
import { computed } from "vue"
import { useRoute, useRouter } from "vue-router"
import { Plus } from "lucide-vue-next"
import { formatChord } from "@/lib/keybindings"
import { useKeybindings } from "@/stores/keybindings"

const props = defineProps<{ flush?: boolean }>()

const route = useRoute()
const router = useRouter()

const active = computed(() => route.path === "/new")

const keybindings = useKeybindings()
const shortcutLabel = computed(() =>
  formatChord(keybindings.chordFor("workspace.newSession")),
)

function navigate(e: Event) {
  e.preventDefault()
  void router.push("/new")
}
</script>

<template>
  <a
    href="#"
    class="block rounded-md border transition-colors"
    :class="[
      props.flush ? 'mx-0 my-0' : 'mx-2 my-1',
      active
        ? 'bg-card border-border shadow-sm'
        : 'border-transparent hover:bg-card/70 active:bg-card',
      'px-3 py-2.5',
    ]"
    @click="navigate"
  >
    <div class="flex items-center gap-3">
      <div
        class="size-9 shrink-0 rounded-lg bg-primary/10 text-primary flex items-center justify-center"
      >
        <Plus class="size-5" />
      </div>
      <div class="min-w-0 flex-1">
        <div class="flex items-baseline justify-between gap-2">
          <span class="font-medium text-foreground">Start a new session</span>
          <span class="text-[11px] text-muted-foreground shrink-0 font-mono">{{ shortcutLabel }}</span>
        </div>
        <p class="text-[11px] text-muted-foreground/65 mt-0.5 truncate">
          Pick a project and send your first message
        </p>
      </div>
    </div>
  </a>
</template>
