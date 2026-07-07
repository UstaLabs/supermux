<script setup lang="ts">
import { computed } from "vue"
import { X, FileIcon, AlertCircle, Check } from "lucide-vue-next"
import { usePromptInput } from "./context"
import { useUploads } from "@/stores/uploads"
import VoiceChip from "@/components/voice/VoiceChip.vue"

const { files, removeFile } = usePromptInput()
const uploads = useUploads()

function isImage(file: { mediaType?: string }): boolean {
  return !!file.mediaType?.startsWith("image/")
}

function isAudio(file: { mediaType?: string }): boolean {
  return !!file.mediaType?.startsWith("audio/")
}

function isVideo(file: { mediaType?: string }): boolean {
  return !!file.mediaType?.startsWith("video/")
}

const items = computed(() => files.value)
</script>

<template>
  <div v-if="items.length > 0" class="flex flex-wrap gap-2 w-full">
    <template v-for="f in items" :key="f.id">
      <VoiceChip
        v-if="isAudio(f)"
        :id="f.id"
        :filename="f.filename"
        :url="f.url"
        :duration-ms="(f as any)._cmuxDurationMs ?? (f.file as any)?._cmuxDurationMs"
      />

      <div
        v-else
        class="relative group flex items-center gap-2 pr-7 rounded-md border border-border bg-card/60 overflow-hidden"
        :class="[
          uploads.byId[f.id]?.status === 'failed' ? 'border-destructive' : '',
        ]"
      >
        <div class="size-10 shrink-0 bg-muted flex items-center justify-center overflow-hidden">
          <img
            v-if="isImage(f) && f.url"
            :src="f.url"
            :alt="f.filename ?? 'attachment'"
            class="size-10 object-cover"
          />
          <video
            v-else-if="isVideo(f) && f.url"
            :src="f.url"
            class="size-10 object-cover bg-black"
            muted
            playsinline
            preload="metadata"
          />
          <FileIcon v-else class="size-5 text-muted-foreground" />
        </div>

        <div class="min-w-0 max-w-[160px] py-1.5 pr-1">
          <div class="text-xs font-medium truncate leading-tight">
            {{ f.filename ?? "file" }}
          </div>
          <div class="text-[10px] text-muted-foreground leading-tight">
            <span v-if="uploads.byId[f.id]?.status === 'uploading'">Uploading…</span>
            <span v-else-if="uploads.byId[f.id]?.status === 'uploaded'">Uploaded</span>
            <span v-else-if="uploads.byId[f.id]?.status === 'failed'" class="text-destructive">Failed</span>
            <span v-else>Ready</span>
          </div>
        </div>

        <div
          v-if="uploads.byId[f.id]?.status === 'uploading'"
          class="absolute inset-0 bg-background/60 flex items-center justify-center pointer-events-none"
        >
          <span class="text-[10px] font-medium tabular-nums text-foreground">
            {{ Math.round(((uploads.byId[f.id] as any)?.progress ?? 0) * 100) }}%
          </span>
        </div>
        <div
          v-if="uploads.byId[f.id]?.status === 'uploading'"
          class="absolute bottom-0 left-0 h-0.5 bg-primary transition-all pointer-events-none"
          :style="{ width: `${Math.round(((uploads.byId[f.id] as any)?.progress ?? 0) * 100)}%` }"
        />
        <div
          v-else-if="uploads.byId[f.id]?.status === 'uploaded'"
          class="absolute top-0.5 right-7 size-3 rounded-full bg-emerald-500 flex items-center justify-center pointer-events-none"
        >
          <Check class="size-2 text-background" />
        </div>
        <div
          v-else-if="uploads.byId[f.id]?.status === 'failed'"
          class="absolute top-0.5 right-7 size-3 rounded-full bg-destructive flex items-center justify-center pointer-events-none"
        >
          <AlertCircle class="size-2 text-background" />
        </div>

        <button
          type="button"
          class="absolute top-1/2 -translate-y-1/2 right-1 size-5 rounded-full hover:bg-muted text-muted-foreground hover:text-foreground transition flex items-center justify-center"
          :aria-label="`Remove ${f.filename ?? 'file'}`"
          @click="removeFile(f.id); uploads.reset(f.id)"
        >
          <X class="size-3" />
        </button>
      </div>
    </template>
  </div>
</template>
