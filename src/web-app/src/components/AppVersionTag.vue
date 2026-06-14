<script setup lang="ts">
const version = __APP_VERSION__
const buildId = __APP_BUILD_ID__
const buildTime = __APP_BUILD_TIME__
</script>

<template>
  <p class="text-[10px] leading-tight text-muted-foreground font-mono truncate" :title="`v${version} · ${buildId} · ${buildTime}`">
    v{{ version }}
  </p>
</template>
