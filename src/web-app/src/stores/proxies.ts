import { defineStore } from "pinia"
import { ref } from "vue"

export interface Proxy {
  domain: string
  sessionName: string
  port: number
  createdAt: string
  isPublic: boolean
  url: string
}

export const useProxies = defineStore("proxies", () => {
  const list = ref<Proxy[]>([])
  function replace(next: Proxy[]) { list.value = next }
  function add(p: Proxy) {
    const idx = list.value.findIndex(x => x.domain === p.domain)
    if (idx >= 0) list.value[idx] = p
    else list.value.push(p)
  }
  function remove(domain: string) {
    list.value = list.value.filter(x => x.domain !== domain)
  }
  return { list, replace, add, remove }
})
