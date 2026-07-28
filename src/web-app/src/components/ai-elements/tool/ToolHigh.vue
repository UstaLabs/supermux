<script setup lang="ts">
import { computed } from "vue"
import Tool from "./Tool.vue"
import ToolTerminal from "./ToolTerminal.vue"
import ToolDiff from "./ToolDiff.vue"
import type { ActivityToolBody } from "@/lib/activity-body"
import { resolveBashParts, resolveEditParts } from "@/lib/activity-body"

const props = defineProps<{
  toolName: string
  summary?: string
  description?: string
  input?: string
  output?: string
  status: "running" | "done" | "error"
  truncated?: boolean
  body?: ActivityToolBody
  resultBody?: ActivityToolBody
}>()

const bash = computed(() => {
  if (props.toolName !== "Bash" && props.body?.kind !== "bash" && props.resultBody?.kind !== "bash") {
    return null
  }
  const parts = resolveBashParts({
    body: props.body,
    resultBody: props.resultBody,
    input: props.input,
    output: props.output,
    toolName: props.toolName,
  })
  if (!parts.command && !parts.output && props.toolName !== "Bash") return null
  return parts
})

const edit = computed(() => {
  if (
    props.toolName !== "Edit"
    && props.toolName !== "Write"
    && props.body?.kind !== "edit"
    && props.body?.kind !== "write"
  ) {
    return null
  }
  return resolveEditParts({
    body: props.body,
    resultBody: props.resultBody,
    input: props.input,
    toolName: props.toolName,
  })
})
</script>

<template>
  <ToolTerminal
    v-if="bash"
    :command="bash.command"
    :output="bash.output"
    :exit-code="bash.exitCode"
    :description="description"
    :status="status"
    :truncated="truncated"
  />
  <ToolDiff
    v-else-if="edit"
    :path="edit.path"
    :mode="edit.mode"
    :diff="edit.diff"
    :content="edit.content"
    :description="description"
    :status="status"
    :truncated="truncated"
  />
  <Tool
    v-else
    :tool-name="toolName"
    :summary="summary"
    :description="description"
    :input="input"
    :output="output"
    :status="status"
    :truncated="truncated"
  />
</template>
