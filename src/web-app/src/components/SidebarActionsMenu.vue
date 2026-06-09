<script setup lang="ts">
import { ref, computed } from "vue"
import { useRouter } from "vue-router"
import { EllipsisVertical, BarChart3, Network, Sun, Moon, Monitor, Settings, Smartphone, Archive, Bot } from "lucide-vue-next"
import {
  DropdownMenuRoot,
  DropdownMenuTrigger,
  DropdownMenuPortal,
  DropdownMenuContent,
  DropdownMenuItem,
} from "reka-ui"
import { useTheme, type ThemeMode } from "@/composables/useTheme"
import { useLayout } from "@/stores/layout"
import { useSessions } from "@/stores/sessions"

const router = useRouter()
const theme = useTheme()
const layout = useLayout()
const sessions = useSessions()
const open = ref(false)

const themeLabel = computed(() => {
  const labels: Record<ThemeMode, string> = { dark: "Dark", light: "Light", auto: "System" }
  return labels[theme.mode.value]
})

const themeIcon = computed(() => {
  if (theme.mode.value === "dark") return Moon
  if (theme.mode.value === "light") return Sun
  return Monitor
})

function navigate(path: string) {
  open.value = false
  router.push(path)
}
function openArchived() {
  open.value = false
  layout.showArchivedPage()
  if (!sessions.archivedLoaded) void sessions.fetchArchived()
}
</script>

<template>
  <DropdownMenuRoot v-model:open="open">
    <DropdownMenuTrigger as-child>
      <button class="text-muted-foreground hover:text-foreground transition" aria-label="Actions">
        <EllipsisVertical class="size-5" />
      </button>
    </DropdownMenuTrigger>
    <DropdownMenuPortal>
      <DropdownMenuContent
        class="z-50 min-w-[180px] overflow-hidden rounded-lg border border-border bg-popover p-1 text-popover-foreground shadow-md data-open:animate-in data-closed:animate-out data-closed:fade-out-0 data-open:fade-in-0 data-closed:zoom-out-95 data-open:zoom-in-95"
        :side-offset="5"
        align="end"
      >
        <DropdownMenuItem
          class="flex cursor-default items-center gap-2.5 rounded-md px-2 py-1.5 text-sm outline-none focus:bg-accent"
          @select="navigate('/personal-assistants')"
        >
          <Bot class="size-4 text-muted-foreground" />
          Personal Assistants
        </DropdownMenuItem>
        <DropdownMenuItem
          class="flex cursor-default items-center gap-2.5 rounded-md px-2 py-1.5 text-sm outline-none focus:bg-accent"
          @select="openArchived"
        >
          <Archive class="size-4 text-muted-foreground" />
          Archived
        </DropdownMenuItem>
        <DropdownMenuItem
          class="flex cursor-default items-center gap-2.5 rounded-md px-2 py-1.5 text-sm outline-none focus:bg-accent"
          @select="navigate('/usage')"
        >
          <BarChart3 class="size-4 text-muted-foreground" />
          Usage
        </DropdownMenuItem>
        <DropdownMenuItem
          class="flex cursor-default items-center gap-2.5 rounded-md px-2 py-1.5 text-sm outline-none focus:bg-accent"
          @select="navigate('/proxies')"
        >
          <Network class="size-4 text-muted-foreground" />
          Proxies
        </DropdownMenuItem>
        <DropdownMenuItem
          class="flex cursor-default items-center gap-2.5 rounded-md px-2 py-1.5 text-sm outline-none focus:bg-accent"
          @select="navigate('/displays')"
        >
          <Monitor class="size-4 text-muted-foreground" />
          Displays
        </DropdownMenuItem>
        <DropdownMenuItem
          class="flex cursor-default items-center gap-2.5 rounded-md px-2 py-1.5 text-sm outline-none focus:bg-accent"
          @select.prevent="theme.cycle()"
        >
          <component :is="themeIcon" class="size-4 text-muted-foreground" />
          Theme: {{ themeLabel }}
        </DropdownMenuItem>
        <DropdownMenuItem
          class="flex cursor-default items-center gap-2.5 rounded-md px-2 py-1.5 text-sm outline-none focus:bg-accent"
          @select="navigate('/settings')"
        >
          <Settings class="size-4 text-muted-foreground" />
          Settings
        </DropdownMenuItem>
        <DropdownMenuItem
          class="flex cursor-default items-center gap-2.5 rounded-md px-2 py-1.5 text-sm outline-none focus:bg-accent"
          @select="navigate('/devices')"
        >
          <Smartphone class="size-4 text-muted-foreground" />
          Devices
        </DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenuPortal>
  </DropdownMenuRoot>
</template>
