<script setup lang="ts">
import { ref, watch } from "vue"
import { ChevronRight, ChevronDown, File, Folder } from "@lucide/vue"
import { api } from "@/api/client"

const props = defineProps<{
  sessionName: string
}>()

const emit = defineEmits<{
  openFile: [path: string]
}>()

const root = ref<TreeNode[]>([])
const expanded = ref(new Set<string>())
const loading = ref(new Set<string>())

async function loadDir(path: string): Promise<TreeNode[]> {
  const entries: FsEntry[] = await api.fsListDir(props.sessionName, path)
  return entries.map((e) => ({
    ...e,
    path: path === "/" ? `/${e.name}` : `${path}/${e.name}`,
    children: e.type === "dir" ? [] : undefined,
    loaded: false,
  }))
}

async function toggleDir(node: TreeNode) {
  if (expanded.value.has(node.path)) {
    expanded.value.delete(node.path)
    return
  }
  if (!node.loaded) {
    loading.value.add(node.path)
    try {
      node.children = await loadDir(node.path)
      node.loaded = true
    } finally {
      loading.value.delete(node.path)
    }
  }
  expanded.value.add(node.path)
}

function handleClick(node: TreeNode) {
  if (node.type === "dir") {
    toggleDir(node)
  } else {
    emit("openFile", node.path)
  }
}

watch(() => props.sessionName, async () => {
  root.value = await loadDir("/")
}, { immediate: true })
</script>

<template>
  <div class="text-[13px] md:text-[13px] text-[15px] select-none overflow-y-auto h-full py-1">
    <TreeItem
      v-for="node in root"
      :key="node.path"
      :node="node"
      :depth="0"
      :expanded="expanded"
      :loading="loading"
      @click="handleClick"
    />
  </div>
</template>

<script lang="ts">
import { defineComponent, h, type PropType } from "vue"

// Declared here, not in <script setup>: TreeItem below needs them, and
// <script setup> bindings are NOT visible to the plain <script> block.
interface FsEntry {
  name: string
  type: "file" | "dir"
  size: number
  modified: string
  ignored: boolean
}

interface TreeNode extends FsEntry {
  path: string
  children?: TreeNode[]
  loaded?: boolean
}


const TreeItem = defineComponent({
  name: "TreeItem",
  props: {
    node: { type: Object as PropType<TreeNode>, required: true },
    depth: { type: Number, required: true },
    expanded: { type: Object as PropType<Set<string>>, required: true },
    loading: { type: Object as PropType<Set<string>>, required: true },
  },
  emits: ["click"],
  setup(props, { emit }) {
    return () => {
      const isDir = props.node.type === "dir"
      const isOpen = props.expanded.has(props.node.path)

      const children: any[] = []
      children.push(
        h("button", {
          class: [
            "flex items-center gap-1.5 w-full px-2 py-1 md:py-0.5 hover:bg-card rounded-md text-left truncate text-muted-foreground hover:text-foreground transition-colors",
            props.node.ignored ? "opacity-50" : "",
          ],
          style: { paddingLeft: `${props.depth * 14 + 10}px` },
          onClick: () => emit("click", props.node),
        }, [
          isDir
            ? h(isOpen ? ChevronDown : ChevronRight, { class: "size-4 md:size-3.5 shrink-0" })
            : h("span", { class: "size-4 md:size-3.5 shrink-0" }),
          h(isDir ? Folder : File, { class: "size-4 md:size-3.5 shrink-0 ml-0.5" }),
          h("span", { class: "truncate ml-1" }, props.node.name),
        ])
      )

      if (isDir && isOpen && props.node.children) {
        for (const child of props.node.children) {
          children.push(h(TreeItem, {
            node: child,
            depth: props.depth + 1,
            expanded: props.expanded,
            loading: props.loading,
            onClick: (n: TreeNode) => emit("click", n),
          }))
        }
      }

      return h("div", null, children)
    }
  },
})
</script>
