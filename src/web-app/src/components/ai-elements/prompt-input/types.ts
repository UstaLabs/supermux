import type { FileUIPart } from 'ai'
import type { Ref } from 'vue'

export interface PromptInputMessage {
  text: string
  files: FileUIPart[]
}

export interface AttachmentFile extends FileUIPart {
  id: string
  file?: File
}

/** Already-uploaded attachment to rehydrate into the composer (e.g. draft restore). */
export interface SeededAttachment {
  file_id: string
  name?: string
  mime?: string
  size?: number
  /** Composer-local id to assign (caller uses this to mark the uploads store). */
  id: string
  /** Preview URL; defaults to `/files/{file_id}` when omitted. */
  url?: string
}

export interface PromptInputContext {
  textInput: Ref<string>
  files: Ref<AttachmentFile[]>
  isLoading: Ref<boolean>
  focused: Ref<boolean>
  fileInputRef: Ref<HTMLInputElement | null>
  setTextInput: (val: string) => void
  addFiles: (files: File[] | FileList) => void
  /** Stage already-uploaded files (no local File blob). Used to restore draft attachments. */
  seedUploadedFiles: (items: SeededAttachment[]) => void
  removeFile: (id: string) => void
  clearFiles: () => void
  clearInput: () => void
  openFileDialog: () => void
  submitForm: () => void
}

export const PROMPT_INPUT_KEY = Symbol('PromptInputContext')
