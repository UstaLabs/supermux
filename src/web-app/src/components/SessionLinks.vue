<script setup lang="ts">
defineOptions({ name: "SessionLinks" })

import { computed } from "vue"
import { Link2, ExternalLink } from "lucide-vue-next"
import {
  DropdownMenuRoot,
  DropdownMenuTrigger,
  DropdownMenuPortal,
  DropdownMenuContent,
  DropdownMenuItem,
} from "reka-ui"
import { useProxies } from "@/stores/proxies"
import { displayUrl } from "@/lib/proxy-url"

const props = defineProps<{ sessionName: string }>()
const proxies = useProxies()

// This session's exposed links, lowest port first for a stable order.
const links = computed(() =>
  proxies.list
    .filter((p) => p.sessionName === props.sessionName)
    .sort((a, b) => a.port - b.port),
)

// When there's exactly one link the icon opens it directly (no menu).
const single = computed(() => (links.value.length === 1 ? links.value[0]! : null))
</script>

<template>
  <!-- Nothing to show until this session has at least one exposed link. -->
  <template v-if="links.length">
    <!-- Single link: the icon is itself the link, opens directly. -->
    <a
      v-if="single"
      :href="single.url"
      target="_blank"
      rel="noopener noreferrer"
      class="cmux-icon-button"
      :title="displayUrl(single.url)"
      :aria-label="`Open ${displayUrl(single.url)}`"
    >
      <Link2 class="size-4" />
    </a>

    <!-- Multiple links: a small dropdown, each item opens its link. -->
    <DropdownMenuRoot v-else>
      <DropdownMenuTrigger as-child>
        <button class="cmux-icon-button" :aria-label="`${links.length} links`" title="Links">
          <Link2 class="size-4" />
        </button>
      </DropdownMenuTrigger>
      <DropdownMenuPortal>
        <DropdownMenuContent
          class="z-50 min-w-[220px] max-w-[min(20rem,90vw)] overflow-hidden rounded-lg border border-border bg-popover p-1 text-popover-foreground shadow-md data-open:animate-in data-closed:animate-out data-closed:fade-out-0 data-open:fade-in-0 data-closed:zoom-out-95 data-open:zoom-in-95"
          :side-offset="5"
          align="end"
        >
          <DropdownMenuItem v-for="link in links" :key="link.domain" as-child>
            <a
              :href="link.url"
              target="_blank"
              rel="noopener noreferrer"
              class="flex cursor-pointer items-center gap-2.5 rounded-md px-2 py-1.5 text-sm outline-none focus:bg-accent"
            >
              <ExternalLink class="size-4 shrink-0 text-muted-foreground" />
              <span class="min-w-0 flex-1 truncate">{{ displayUrl(link.url) }}</span>
              <span class="shrink-0 text-[11px] tabular-nums text-muted-foreground">:{{ link.port }}</span>
              <span
                v-if="link.isPublic"
                class="shrink-0 text-[10px] font-medium uppercase tracking-wide px-1.5 py-0.5 rounded bg-amber-500/15 text-amber-600 dark:text-amber-400"
              >Public</span>
            </a>
          </DropdownMenuItem>
        </DropdownMenuContent>
      </DropdownMenuPortal>
    </DropdownMenuRoot>
  </template>
</template>
