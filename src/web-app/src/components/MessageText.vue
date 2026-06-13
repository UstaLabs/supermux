<script setup lang="ts">
import { computed } from "vue"
import { toast } from "vue-sonner"
import { renderMarkdown } from "@/lib/markdown"

const props = defineProps<{ content: string }>()
const emit = defineEmits<{ openFile: [path: string, line?: number, endLine?: number] }>()
const rendered = computed(() => renderMarkdown(props.content))

async function onClick(e: MouseEvent) {
  const target = e.target as HTMLElement

  const copyBtn = target.closest(".code-copy-btn") as HTMLElement | null
  if (copyBtn) {
    // The code lives in the sibling <pre><code>; strip marked's trailing newline.
    const code = copyBtn.closest(".code-block")?.querySelector("pre code")?.textContent ?? ""
    try {
      await navigator.clipboard.writeText(code.replace(/\n$/, ""))
      copyBtn.classList.add("copied")
      setTimeout(() => copyBtn.classList.remove("copied"), 1500)
    } catch {
      toast.error("Couldn't copy to clipboard")
    }
    return
  }

  const link = target.closest("a.file-link") as HTMLElement | null
  if (link) {
    e.preventDefault()
    const path = link.dataset.path
    if (!path) return
    const line = link.dataset.line ? Number(link.dataset.line) : undefined
    const endLine = link.dataset.lineEnd ? Number(link.dataset.lineEnd) : undefined
    emit("openFile", path, line, endLine)
  }
}
</script>

<template>
  <div class="md-body text-[14.5px] leading-relaxed break-words" v-html="rendered" @click="onClick" />
</template>

<style scoped>
.md-body :deep(p) { margin: 0; }
.md-body :deep(p + p) { margin-top: 0.5rem; }
.md-body :deep(ul),
.md-body :deep(ol) { margin: 0.25rem 0; padding-left: 1.25rem; }
.md-body :deep(li) { margin: 0.125rem 0; }
.md-body :deep(li > p:first-child) { display: inline; }
.md-body :deep(a) { text-decoration: underline; text-underline-offset: 2px; }
.md-body :deep(a.file-link) {
  color: inherit;
  text-decoration: underline;
  text-decoration-style: dotted;
  text-underline-offset: 3px;
  cursor: pointer;
}
.md-body :deep(a.file-link:hover) { text-decoration-style: solid; }
.md-body :deep(code) {
  font-family: ui-monospace, SFMono-Regular, "JetBrains Mono", Menlo, Consolas, monospace;
  font-size: 0.875em;
  padding: 0.1em 0.35em;
  border-radius: 0.35rem;
  background: rgba(255, 255, 255, 0.08);
}
.md-body :deep(.code-block) { position: relative; margin: 0.5rem 0; }
.md-body :deep(pre) {
  margin: 0;
  padding: 0.75rem 0.9rem;
  border-radius: 0.5rem;
  overflow-x: auto;
  font-size: 0.85em;
  line-height: 1.45;
  background: rgba(0, 0, 0, 0.35);
}
.md-body :deep(pre code) {
  padding: 0;
  background: transparent;
  font-size: inherit;
}
/* Copy button lives on the non-scrolling .code-block wrapper, so it stays
   pinned to the top-right while the <pre> scrolls horizontally. */
.md-body :deep(.code-copy-btn) {
  position: absolute;
  top: 0.4rem;
  right: 0.4rem;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 1.8rem;
  height: 1.8rem;
  padding: 0;
  /* Near-opaque so it cleanly covers any code beneath it on long first lines. */
  border: 1px solid rgba(255, 255, 255, 0.12);
  border-radius: 0.4rem;
  color: rgba(255, 255, 255, 0.65);
  background: rgba(15, 23, 42, 0.94);
  cursor: pointer;
  transition: color 0.12s, background 0.12s, border-color 0.12s;
  -webkit-tap-highlight-color: transparent;
}
.md-body :deep(.code-copy-btn:hover) {
  color: #fff;
  background: rgba(30, 41, 59, 0.98);
  border-color: rgba(255, 255, 255, 0.22);
}
.md-body :deep(.code-copy-btn svg) { display: block; width: 0.95rem; height: 0.95rem; }
.md-body :deep(.code-copy-btn .icon-check) { display: none; }
.md-body :deep(.code-copy-btn.copied) { color: #4ade80; border-color: rgba(74, 222, 128, 0.4); }
.md-body :deep(.code-copy-btn.copied .icon-copy) { display: none; }
.md-body :deep(.code-copy-btn.copied .icon-check) { display: block; }
.md-body :deep(blockquote) {
  border-left: 3px solid currentColor;
  opacity: 0.85;
  padding-left: 0.7rem;
  margin: 0.4rem 0;
}
.md-body :deep(table) {
  border-collapse: collapse;
  margin: 0.5rem 0;
  font-size: 0.9em;
}
.md-body :deep(th),
.md-body :deep(td) {
  border: 1px solid currentColor;
  padding: 0.2rem 0.5rem;
  border-color: rgba(255, 255, 255, 0.15);
}
.md-body :deep(hr) { border: none; border-top: 1px solid rgba(255,255,255,0.1); margin: 0.75rem 0; }
</style>
