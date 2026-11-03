// Fetches each Agent's avatar (GET /agents/<id>/avatar) and refetches when its hash changes —
// the session snapshot carries the hash, and `agent_avatar_changed` updates it live.
package dev.supermux.ui.session

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import dev.supermux.net.BrokerApi
import dev.supermux.proto.SessionInfo
import kotlinx.coroutines.CancellationException

@Composable
fun rememberAgentAvatars(
    sessions: List<SessionInfo>,
    apiFor: (String) -> BrokerApi?,
    /** More Agents to cover (id → avatar hash): the archived ones in the sidebar's fold. */
    extra: Map<String, String> = emptyMap(),
): Map<String, AvatarRig> {
    val cache = remember { mutableStateMapOf<String, Pair<String, AvatarRig>>() }
    val wanted = sessions.filter { it.role == "personal_assistant" && it.avatar != null }.associate { it.id to it.avatar!! } + extra
    LaunchedEffect(wanted) {
        cache.keys.retainAll(wanted.keys)
        // Card #54: the launch trace's "Agents have their pictures" mark — fetched one at a time.
        val start = dev.supermux.util.PerfTrace.elapsedMs()
        var fetched = 0
        for ((id, rev) in wanted) {
            if (cache[id]?.first == rev) continue
            fetched++
            val text = try {
                apiFor(id)?.getAgentAvatar(id)
            } catch (c: CancellationException) {
                throw c
            } catch (_: Throwable) {
                null
            }
            text?.let(::parseAvatarRig)?.let { cache[id] = rev to it }
        }
        if (fetched > 0) {
            dev.supermux.util.PerfTrace.milestone(
                "avatars.loaded",
                "fetched" to fetched,
                "shown" to cache.size,
                "wanted" to wanted.size,
                "ms" to dev.supermux.util.PerfTrace.elapsedMs() - start,
            )
        }
    }
    return cache.mapValues { it.value.second }
}
