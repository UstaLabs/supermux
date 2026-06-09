import { ref, type Ref } from "vue"

export interface LightboxItem {
  file_id: string
  name?: string
}

const current = ref<LightboxItem | null>(null) as Ref<LightboxItem | null>

export function useLightbox() {
  return {
    current,
    open(item: LightboxItem) { current.value = item },
    close() { current.value = null },
  }
}
