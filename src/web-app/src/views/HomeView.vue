<script setup lang="ts">
import { onMounted, watch } from "vue"
import { useRouter } from "vue-router"
import { useIsDesktop } from "@/composables/useIsDesktop"
import SessionListView from "@/views/SessionListView.vue"

const isDesktop = useIsDesktop()
const router = useRouter()

function redirectDesktopHome() {
  if (isDesktop.value && router.currentRoute.value.path === "/") {
    void router.replace("/new")
  }
}

onMounted(redirectDesktopHome)
watch(isDesktop, redirectDesktopHome)
</script>

<template>
  <SessionListView v-if="!isDesktop" />
</template>
