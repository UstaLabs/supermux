import { defineStore } from "pinia"
import { ref } from "vue"

export interface AgentLoginState { kind: string; phase: string; url?: string; code?: string; error?: string }

export const ONBOARDING_STEP_LABELS = ["Welcome", "Agents", "Git Hosting", "Connectivity", "Done"] as const

const LAST_STEP = ONBOARDING_STEP_LABELS.length - 1

export const useOnboarding = defineStore("onboarding", () => {
  const onboarded = ref<boolean | null>(null)
  const step = ref(0)
  const loginStates = ref<Record<string, AgentLoginState>>({})

  function setOnboarded(v: boolean) { onboarded.value = v }
  function setAgentLoginState(kind: string, st: AgentLoginState) { loginStates.value = { ...loginStates.value, [kind]: st } }
  function loginState(kind: string): AgentLoginState | undefined { return loginStates.value[kind] }
  function setStep(n: number) { step.value = Math.max(0, Math.min(LAST_STEP, n)) }
  function next() { setStep(step.value + 1) }
  function prev() { setStep(step.value - 1) }

  return { onboarded, step, loginStates, setOnboarded, setAgentLoginState, loginState, setStep, next, prev }
})
