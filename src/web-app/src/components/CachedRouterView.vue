<script setup lang="ts">
import { defineAsyncComponent, watch } from "vue"
import { useRoute } from "vue-router"
import { useSessionCache } from "@/stores/sessionCache"

const ChatView = defineAsyncComponent(() => import("@/views/ChatView.vue"))

const route = useRoute()
const sessionCache = useSessionCache()

watch(
  () => [route.name, route.params.id] as const,
  ([name, id]) => {
    if (name === "session-chat" && typeof id === "string" && id) sessionCache.visit(id)
  },
  { immediate: true },
)
</script>

<template>
  <router-view v-slot="{ Component, route: r }">
    <template v-if="r.name === 'session-chat'">
      <keep-alive include="ChatView">
        <ChatView
          v-for="sid in sessionCache.liveIds"
          v-show="String(r.params.id) === sid"
          :id="sid"
          :key="sid"
        />
      </keep-alive>
    </template>
    <component v-else :is="Component" :key="r.fullPath" />
  </router-view>
</template>
