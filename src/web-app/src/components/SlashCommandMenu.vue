<script setup lang="ts">
import { computed, ref, watch, onMounted, onBeforeUnmount, nextTick } from "vue"
import type { SlashCommand } from "@/stores/commands"
import { activeSlashToken } from "@/lib/slash-token"
import { usePromptInput } from "@/components/ai-elements/prompt-input/context"

// Lives INSIDE <PromptInput> so it can read/write the composer text via context.
// The popover itself is teleported to <body> with fixed positioning, because the
// composer's InputGroup is `relative` + `overflow-hidden` and would otherwise
// clip an absolutely-positioned popover rendered above it.
const props = defineProps<{ commands: SlashCommand[]; loading?: boolean }>()
const emit = defineEmits<{ (e: "control", cmd: SlashCommand): void }>()

const { textInput, setTextInput } = usePromptInput()
const anchor = ref<HTMLElement | null>(null)
const cursor = ref(0)

// Escape dismisses without clearing the text; it re-arms when the text changes.
const dismissed = ref(false)
watch(() => textInput.value, () => {
  dismissed.value = false
  void nextTick(syncCursor)
})

function textarea(): HTMLTextAreaElement | null {
  return form()?.querySelector("textarea") ?? null
}

function syncCursor() {
  const ta = textarea()
  cursor.value = ta?.selectionStart ?? textInput.value.length
}

const activeToken = computed(() => activeSlashToken(textInput.value, cursor.value))

// Open while the cursor sits in a `/token` with no space yet. An agent insert
// appends a trailing space, which closes the menu so the user types args.
const open = computed(() => !dismissed.value && activeToken.value !== null)
const query = computed(() => activeToken.value?.query ?? "")

const filtered = computed(() =>
  props.commands.filter(
    (c) => c.name.toLowerCase().includes(query.value) || (c.description ?? "").toLowerCase().includes(query.value),
  ),
)
const control = computed(() => filtered.value.filter((c) => c.family === "control"))
const agent = computed(() => filtered.value.filter((c) => c.family === "agent"))
// Flat selection order matches display order (control first, then agent).
const items = computed(() => [...control.value, ...agent.value])
const activeIndex = ref(0)
watch([query, open], () => { activeIndex.value = 0 })
// "loading" only while the broker is still discovering agent commands; once
// resolved with none, show a quiet empty note instead of a perpetual spinner.
const showLoading = computed(() => props.loading && agent.value.length === 0)
const showAgentEmpty = computed(() => !props.loading && agent.value.length === 0)

const pop = ref<HTMLElement | null>(null)
watch(activeIndex, (i) => {
  void nextTick(() => pop.value?.querySelector(`[data-idx="${i}"]`)?.scrollIntoView({ block: "nearest" }))
})

const pos = ref({ left: 0, bottom: 0, width: 0 })
function form(): HTMLFormElement | null {
  return (anchor.value?.closest("form") as HTMLFormElement | null) ?? null
}
function updatePos() {
  const f = form()
  if (!f) return
  const r = f.getBoundingClientRect()
  pos.value = { left: r.left, bottom: window.innerHeight - r.top + 8, width: r.width }
}
const popStyle = computed(() => ({
  left: `${pos.value.left}px`,
  bottom: `${pos.value.bottom}px`,
  width: `${pos.value.width}px`,
}))

watch(open, (v) => { if (v) void nextTick(updatePos) })

// Intercept arrow/enter/escape on the composer while open. Capture phase so we
// run before PromptInputTextarea's own Enter-submits handler.
function onKeydown(e: KeyboardEvent) {
  syncCursor()
  if (!open.value || items.value.length === 0) return
  if (e.key === "ArrowDown") {
    e.preventDefault(); e.stopPropagation()
    activeIndex.value = (activeIndex.value + 1) % items.value.length
  } else if (e.key === "ArrowUp") {
    e.preventDefault(); e.stopPropagation()
    activeIndex.value = (activeIndex.value - 1 + items.value.length) % items.value.length
  } else if (e.key === "Enter") {
    e.preventDefault(); e.stopPropagation()
    const cmd = items.value[activeIndex.value]
    if (cmd) pick(cmd)
  } else if (e.key === "Escape") {
    e.preventDefault(); e.stopPropagation()
    dismissed.value = true
  }
}

onMounted(() => {
  const ta = textarea()
  ta?.addEventListener("keyup", syncCursor)
  ta?.addEventListener("click", syncCursor)
  ta?.addEventListener("select", syncCursor)
  window.addEventListener("resize", updatePos)
  window.addEventListener("scroll", updatePos, true)
  document.addEventListener("keydown", onKeydown, true)
})
onBeforeUnmount(() => {
  const ta = textarea()
  ta?.removeEventListener("keyup", syncCursor)
  ta?.removeEventListener("click", syncCursor)
  ta?.removeEventListener("select", syncCursor)
  window.removeEventListener("resize", updatePos)
  window.removeEventListener("scroll", updatePos, true)
  document.removeEventListener("keydown", onKeydown, true)
})

function replaceActiveToken(insert: string) {
  const text = textInput.value
  const token = activeSlashToken(text, cursor.value)
  if (!token) {
    setTextInput(insert)
    return
  }
  const end = cursor.value
  setTextInput(text.slice(0, token.start) + insert + text.slice(end))
}

function pick(cmd: SlashCommand) {
  if (cmd.family === "agent") {
    replaceActiveToken(cmd.insertText ?? `${cmd.sigil}${cmd.name} `)
    form()?.querySelector("textarea")?.focus()
    return
  }
  replaceActiveToken("")
  emit("control", cmd)
}
</script>

<template>
  <span ref="anchor" class="hidden" aria-hidden="true" />
  <Teleport to="body">
    <div
      v-if="open"
      ref="pop"
      :style="popStyle"
      class="fixed z-[100] max-h-72 overflow-y-auto rounded-xl border border-foreground/10 bg-popover text-popover-foreground shadow-xl ring-1 ring-foreground/5 p-1.5"
    >
      <div v-if="control.length" class="px-2 pt-1 pb-0.5 text-[11px] font-medium uppercase tracking-wide text-muted-foreground">Control</div>
      <button
        v-for="(c, i) in control"
        :key="c.id"
        :data-idx="i"
        type="button"
        class="flex w-full items-baseline gap-2 rounded-lg px-2 py-1.5 text-left hover:bg-accent"
        :class="activeIndex === i ? 'bg-accent' : ''"
        @mousedown.prevent
        @mousemove="activeIndex = i"
        @click="pick(c)"
      >
        <span class="font-medium shrink-0">/{{ c.name }}</span>
        <span class="truncate text-xs text-muted-foreground">{{ c.description }}</span>
      </button>

      <div class="px-2 pt-1.5 pb-0.5 text-[11px] font-medium uppercase tracking-wide text-muted-foreground">Agent</div>
      <div v-if="showLoading" class="px-2 py-1.5 text-xs text-muted-foreground">loading agent commands…</div>
      <div v-else-if="showAgentEmpty" class="px-2 py-1.5 text-xs text-muted-foreground">no agent commands for this session</div>
      <button
        v-for="(c, i) in agent"
        :key="c.id"
        :data-idx="control.length + i"
        type="button"
        class="flex w-full items-baseline gap-2 rounded-lg px-2 py-1.5 text-left hover:bg-accent"
        :class="activeIndex === control.length + i ? 'bg-accent' : ''"
        @mousedown.prevent
        @mousemove="activeIndex = control.length + i"
        @click="pick(c)"
      >
        <span class="font-medium shrink-0">{{ c.sigil }}{{ c.name }}</span>
        <span class="truncate text-xs text-muted-foreground">{{ c.description }}</span>
      </button>

      <div v-if="!filtered.length && !showLoading && !showAgentEmpty" class="px-2 py-2 text-xs text-muted-foreground">No matching commands</div>
    </div>
  </Teleport>
</template>
