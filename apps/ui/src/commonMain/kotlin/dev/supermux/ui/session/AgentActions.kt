// The Agent actions the sidebar offers — create, rename, archive, make default, change character —
// against one host (desktop) or the fleet's active host (Android, web).
package dev.supermux.ui.session

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import dev.supermux.net.ArchivedDto
import dev.supermux.net.ModelInfo
import dev.supermux.net.PADto
import dev.supermux.net.ReasoningResponse
import dev.supermux.state.AccountResult
import dev.supermux.state.FleetStore
import dev.supermux.state.HostStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/** Every broker call the sidebar's Agent actions make (create, rename, archive, default, avatar). */
@Immutable
class AgentsActions(
    val load: suspend () -> AccountResult<List<PADto>> = { AccountResult.Ok(emptyList()) },
    /** Installed providers (agent kinds), in the host's order; null when the host couldn't be asked. */
    val providers: suspend () -> List<String>? = { emptyList() },
    val models: suspend (provider: String) -> List<ModelInfo> = { emptyList() },
    /** Effort (reasoning) levels for a provider + model; null when they can't be loaded. */
    val efforts: suspend (provider: String, model: String) -> ReasoningResponse? = { _, _ -> null },
    /** [avatar] is the gallery character's JSON (FORMAT.md); null keeps the generated default. */
    val create: suspend (name: String, provider: String, model: String?, avatar: String?, effort: String?) -> AccountResult<Unit> =
        { _, _, _, _, _ -> AccountResult.Ok(Unit) },
    val kill: suspend (id: String) -> AccountResult<Unit> = { AccountResult.Ok(Unit) },
    /** Replace an Agent's avatar with a gallery character's JSON. */
    val setAvatar: suspend (id: String, json: String) -> AccountResult<Unit> = { _, _ -> AccountResult.Ok(Unit) },
    val setDefault: suspend (id: String) -> AccountResult<Unit> = { AccountResult.Ok(Unit) },
    val rename: suspend (id: String, name: String) -> AccountResult<Unit> = { _, _ -> AccountResult.Ok(Unit) },
    /** Archived Agents (newest first); empty when none or the host can't be asked. */
    val archived: suspend () -> List<ArchivedDto> = { emptyList() },
    /** Bring an archived Agent back (POST /sessions/<id>/resume). */
    val restore: suspend (id: String) -> Boolean = { false },
    /** An Agent's avatar JSON (live or archived); null when it has none. */
    val avatar: suspend (id: String) -> String? = { null },
    /** Emits when the host says the Agent list changed (`agents_changed`) or synced again after a
     *  reconnect; the list reloads. */
    val changes: Flow<Unit> = emptyFlow(),
)

/** [AgentsActions] against one paired host — desktop's wiring. */
@Composable
fun rememberAgentsActions(app: HostStore): AgentsActions = remember(app) {
    AgentsActions(
        load = { app.listAgents() },
        providers = { app.agentStatuses()?.filter { it.installed }?.map { it.kind } },
        models = { app.launcherModels(it) },
        efforts = { provider, model -> app.launcherReasoning(provider, model) },
        create = { name, provider, model, avatar, effort -> app.createAgent(name, provider, model, avatar, effort) },
        kill = { id -> app.killAgent(id) },
        setAvatar = { id, json -> app.setAgentAvatar(id, json) },
        setDefault = { id -> app.setDefaultAgent(id) },
        rename = { id, name -> app.renameAgent(id, name) },
        archived = { app.archived().filter { it.role == "personal_assistant" }.sortedByDescending { it.killed_at } },
        restore = { id -> app.resume(id) },
        avatar = { id -> runCatching { app.api.getAgentAvatar(id) }.getOrNull() },
        changes = app.agentsReload,
    )
}

/** [AgentsActions] against the fleet's ACTIVE host — Android's and web's wiring. */
@Composable
fun rememberAgentsActions(fleet: FleetStore): AgentsActions = remember(fleet) {
    AgentsActions(
        load = { fleet.listAgents() },
        providers = { fleet.agentStatuses()?.filter { it.installed }?.map { it.kind } },
        models = { fleet.launcherModels(it) },
        efforts = { provider, model -> fleet.launcherReasoning(provider, model) },
        create = { name, provider, model, avatar, effort -> fleet.createAgent(name, provider, model, avatar, effort) },
        kill = { id -> fleet.killAgent(id) },
        setAvatar = { id, json -> fleet.setAgentAvatar(id, json) },
        setDefault = { id -> fleet.setDefaultAgent(id) },
        rename = { id, name -> fleet.renameAgent(id, name) },
        archived = { fleet.archived().filter { it.role == "personal_assistant" }.sortedByDescending { it.killed_at } },
        restore = { id -> fleet.resume(id) },
        avatar = { id -> runCatching { (fleet.appFor(id) ?: fleet.activeApp())?.api?.getAgentAvatar(id) }.getOrNull() },
        changes = fleet.activeAgentsChanged,
    )
}
