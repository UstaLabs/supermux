<script setup lang="ts">
import { computed } from "vue"
import { useRouter } from "vue-router"
import { EllipsisVertical, BarChart3, Network, Sun, Moon, Monitor, Settings, Smartphone, Archive } from "lucide-vue-next"
import { Sheet, SheetContent, SheetTrigger, SheetTitle } from "@/components/ui/sheet"
import { useTheme, type ThemeMode } from "@/composables/useTheme"

const router = useRouter()
const theme = useTheme()
const open = defineModel<boolean>("open", { default: false })

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
</script>

<template>
  <Sheet v-model:open="open">
    <SheetTrigger as-child>
      <button class="text-muted-foreground hover:text-foreground transition" aria-label="Actions">
        <EllipsisVertical class="size-5" />
      </button>
    </SheetTrigger>
    <SheetContent side="bottom" :show-close-button="false" class="pb-[calc(env(safe-area-inset-bottom,0px)+1rem)]">
      <SheetTitle class="sr-only">Actions</SheetTitle>
      <nav class="flex flex-col">
        <button
          class="flex items-center gap-3 px-4 py-3 text-sm text-foreground hover:bg-muted/50 active:bg-muted transition rounded-md"
          @click="navigate('/archived')"
        >
          <Archive class="size-5 text-muted-foreground" />
          <span>Archived</span>
        </button>
        <button
          class="flex items-center gap-3 px-4 py-3 text-sm text-foreground hover:bg-muted/50 active:bg-muted transition rounded-md"
          @click="navigate('/usage')"
        >
          <BarChart3 class="size-5 text-muted-foreground" />
          <span>Usage</span>
        </button>
        <button
          class="flex items-center gap-3 px-4 py-3 text-sm text-foreground hover:bg-muted/50 active:bg-muted transition rounded-md"
          @click="navigate('/proxies')"
        >
          <Network class="size-5 text-muted-foreground" />
          <span>Proxies</span>
        </button>
        <button
          class="flex items-center gap-3 px-4 py-3 text-sm text-foreground hover:bg-muted/50 active:bg-muted transition rounded-md"
          @click="navigate('/displays')"
        >
          <Monitor class="size-5 text-muted-foreground" />
          <span>Displays</span>
        </button>
        <button
          class="flex items-center gap-3 px-4 py-3 text-sm text-foreground hover:bg-muted/50 active:bg-muted transition rounded-md"
          @click="theme.cycle()"
        >
          <component :is="themeIcon" class="size-5 text-muted-foreground" />
          <span>Theme: {{ themeLabel }}</span>
        </button>
        <button
          class="flex items-center gap-3 px-4 py-3 text-sm text-foreground hover:bg-muted/50 active:bg-muted transition rounded-md"
          @click="navigate('/settings')"
        >
          <Settings class="size-5 text-muted-foreground" />
          <span>Settings</span>
        </button>
        <button
          class="flex items-center gap-3 px-4 py-3 text-sm text-foreground hover:bg-muted/50 active:bg-muted transition rounded-md"
          @click="navigate('/devices')"
        >
          <Smartphone class="size-5 text-muted-foreground" />
          <span>Devices</span>
        </button>
      </nav>
    </SheetContent>
  </Sheet>
</template>
