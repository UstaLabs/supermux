import { createRouter, createWebHistory } from "vue-router"
import HomeView from "./views/HomeView.vue"
import ArchivedListView from "./views/ArchivedListView.vue"
import ChatView from "./views/ChatView.vue"
import DevicesView from "./views/DevicesView.vue"
import UsageView from "./views/UsageView.vue"
import ProxiesView from "./views/ProxiesView.vue"
import DisplaysView from "./views/DisplaysView.vue"
import EditorSettingsView from "./views/EditorSettingsView.vue"
import SettingsIndexView from "./views/SettingsIndexView.vue"
import AssistantSettingsView from "./views/AssistantSettingsView.vue"
import CuratorSettingsView from "./views/CuratorSettingsView.vue"
import AgentSettingsView from "./views/AgentSettingsView.vue"
import { useOnboarding } from "./stores/onboarding"

import KeyboardSettingsView from "./views/KeyboardSettingsView.vue"
import SystemSettingsView from "./views/SystemSettingsView.vue"
import SessionLauncherView from "./views/SessionLauncherView.vue"
import PersonalAssistantsView from "./views/PersonalAssistantsView.vue"
export const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: "/", component: HomeView },
    { path: "/new", component: SessionLauncherView },
    { path: "/archived", component: ArchivedListView, meta: { fullScreen: true } },
    { path: "/s/:id", name: "session-chat", component: ChatView, props: true },
    { path: "/devices", component: DevicesView, meta: { fullScreen: true } },
    { path: "/usage", component: UsageView, meta: { fullScreen: true } },
    { path: "/proxies", component: ProxiesView, meta: { fullScreen: true } },
    { path: "/displays", component: DisplaysView, meta: { fullScreen: true } },
    { path: "/settings", component: SettingsIndexView, meta: { fullScreen: true } },
    { path: "/settings/assistant", component: AssistantSettingsView, meta: { fullScreen: true } },
    { path: "/settings/agents", component: AgentSettingsView, meta: { fullScreen: true } },
    { path: "/settings/curator", component: CuratorSettingsView, meta: { fullScreen: true } },
    { path: "/settings/editor", component: EditorSettingsView, meta: { fullScreen: true } },
    { path: "/setup", component: () => import("./views/SetupView.vue"), meta: { fullScreen: true } },
    { path: "/settings/keyboard", component: KeyboardSettingsView, meta: { fullScreen: true } },
    { path: "/settings/system", component: SystemSettingsView, meta: { fullScreen: true } },
    { path: "/personal-assistants", component: PersonalAssistantsView, meta: { fullScreen: true } },
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
