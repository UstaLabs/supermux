import { computed } from "vue"
import { useColorMode } from "@vueuse/core"

export type ThemeMode = "dark" | "light" | "auto"

const modes: ThemeMode[] = ["dark", "light", "auto"]

const colorMode = useColorMode({
  storageKey: "color-mode",
  emitAuto: true,
})

export function useTheme() {
  const mode = computed<ThemeMode>(() => {
    const v = colorMode.store.value
    if (v === "dark" || v === "light") return v
    return "auto"
  })

  function cycle() {
    const i = modes.indexOf(mode.value)
    colorMode.store.value = modes[(i + 1) % modes.length]!
  }

  function set(m: ThemeMode) {
    colorMode.store.value = m
  }

  return { mode, cycle, set }
}
