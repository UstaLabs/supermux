import { createRouter, createWebHistory } from "vue-router"
import { useOnboarding } from "./stores/onboarding"

export const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: "/", component: () => import("./views/HomeView.vue") },
    { path: "/new", component: () => import("./views/SessionLauncherView.vue") },
    { path: "/archived", component: () => import("./views/ArchivedListView.vue"), meta: { fullScreen: true } },
    { path: "/s/:id", name: "session-chat", component: () => import("./views/ChatView.vue"), props: true },
    { path: "/devices", component: () => import("./views/DevicesView.vue"), meta: { fullScreen: true } },
    { path: "/usage", component: () => import("./views/UsageView.vue"), meta: { fullScreen: true } },
    { path: "/proxies", component: () => import("./views/ProxiesView.vue"), meta: { fullScreen: true } },
    { path: "/displays", component: () => import("./views/DisplaysView.vue"), meta: { fullScreen: true } },
    { path: "/settings", component: () => import("./views/SettingsIndexView.vue"), meta: { fullScreen: true } },
    { path: "/settings/assistant", component: () => import("./views/AssistantSettingsView.vue"), meta: { fullScreen: true } },
    { path: "/settings/agents", component: () => import("./views/AgentSettingsView.vue"), meta: { fullScreen: true } },
    { path: "/settings/curator", component: () => import("./views/CuratorSettingsView.vue"), meta: { fullScreen: true } },
    { path: "/settings/voice", component: () => import("./views/VoiceSettingsView.vue"), meta: { fullScreen: true } },
    { path: "/settings/editor", component: () => import("./views/EditorSettingsView.vue"), meta: { fullScreen: true } },
    { path: "/setup", component: () => import("./views/SetupView.vue"), meta: { fullScreen: true } },
    { path: "/settings/git-hosting", component: () => import("./views/GitHostingSettingsView.vue"), meta: { fullScreen: true } },
    { path: "/settings/keyboard", component: () => import("./views/KeyboardSettingsView.vue"), meta: { fullScreen: true } },
    { path: "/settings/system", component: () => import("./views/SystemSettingsView.vue"), meta: { fullScreen: true } },
    { path: "/personal-assistants", component: () => import("./views/PersonalAssistantsView.vue"), meta: { fullScreen: true } },
  ],
})

// Send unfinished installs to the setup wizard. Only redirect when we KNOW
// onboarding isn't done (onboarded === false); null = unknown (pre-snapshot) so
// we don't bounce. The desktop latest-session redirect that used to live here
// was removed on main (HomeView owns that now).
router.beforeEach((to) => {
  if (to.path === "/setup") return true
  try {
    const onboarding = useOnboarding()
    if (onboarding.onboarded === false) return { path: "/setup" }
  } catch { /* pinia not ready yet */ }
  return true
})
