import { ref, type Ref } from "vue"
import { useIntersectionObserver } from "@vueuse/core"

/** True once the target enters (or nears) the viewport; stays true after. */
export function useLazyInView(
  target: Ref<HTMLElement | null | undefined>,
  rootMargin = "400px 0px",
): Ref<boolean> {
  const shouldLoad = ref(false)

  useIntersectionObserver(
    target,
    ([entry]) => {
      if (entry?.isIntersecting) shouldLoad.value = true
    },
    { rootMargin, threshold: 0 },
  )

  return shouldLoad
}
