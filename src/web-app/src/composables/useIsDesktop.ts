import { useMediaQuery } from "@vueuse/core"

// Reactive boolean: true when the viewport is ≥ 1024px (Tailwind `lg`).
// Drives the split layout vs full-bleed mobile decision.
export function useIsDesktop() {
  return useMediaQuery("(min-width: 1024px)")
}
