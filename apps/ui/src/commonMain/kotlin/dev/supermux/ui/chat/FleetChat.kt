// Cluster G7: the FLEET builders for the chat panel's two holders.
//
// `rememberChatState(HostStore, …)` / `rememberChatActions(HostStore, …)` (ChatPanel.kt) are the
// single-host shape desktop has had since cluster D. Android's workspace built the same two objects
// by hand, inline, from `vm.fleet` — 45 lines that could drift from the panel's contract. These are
// those lines, once, keyed by session id so every call routes to the owning host.
package dev.supermux.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import dev.supermux.state.FleetStore
import dev.supermux.ui.editor.WalkthroughState
import dev.supermux.ui.platform.LocalPlatform

/** [ChatState] collected off a [FleetStore] for one session. */
@Composable
fun rememberChatState(fleet: FleetStore, sessionId: String): ChatState {
    val messages by fleet.messages.collectAsState()
    val activity by fleet.activity.collectAsState()
    val agentState by fleet.agentState.collectAsState()
    val pendingSend by fleet.pendingSend.collectAsState()
    val commands by fleet.commands.collectAsState()
    val commandsResolved by fleet.commandsResolved.collectAsState()
    val bgTasks by fleet.bgTasks.collectAsState()
    // Gate the holder READ on the capability: a host built without a WalkthroughSeam has none.
    val walkthrough = if (LocalPlatform.current.caps.walkthrough) {
        fleet.walkthroughState<WalkthroughState>(sessionId)
    } else {
        null
    }
    return ChatState(
        messages = messages[sessionId].orEmpty(),
        activity = activity[sessionId].orEmpty(),
        agent = agentState[sessionId],
        bgTasks = bgTasks[sessionId].orEmpty(),
        sending = sessionId in pendingSend,
        commands = commands[sessionId].orEmpty(),
        commandsResolved = commandsResolved[sessionId] ?: false,
        walkthroughUnread = walkthrough?.unreadReplies ?: 0,
        walkthroughUnreadStepId = walkthrough?.unreadStepId,
    )
}

/** [ChatActions] wired to a [FleetStore] for one session. */
@Composable
fun rememberChatActions(fleet: FleetStore, sessionId: String): ChatActions =
    remember(fleet, sessionId) {
        ChatActions(
            send = { text, atts -> fleet.sendWith(sessionId, text, atts) },
            interrupt = { fleet.interrupt(sessionId) },
            upload = { source, name, mime, kind, onProgress ->
                fleet.uploadResumable(sessionId, source, name, mime, kind, onProgress)
            },
            transcribeAudio = { bytes, name, mime -> fleet.transcribeAudio(sessionId, bytes, name, mime) },
            loadBytes = { fleet.fileBytes(it) },
            composer = ComposerActions(
                loadDraft = { fleet.loadDraft(it) },
                saveDraft = { id, t -> fleet.saveDraft(id, t) },
                consumePendingFirst = { id ->
                    fleet.consumePendingFirst(id)?.let { it.text to it.attachments }
                },
                transcribeDraft = { draft -> fleet.transcribeDraft(sessionId, draft) },
                loadGlossary = { fleet.fetchGlossary().orEmpty() },
                gitFetch = { fleet.gitFetch(sessionId) },
                gitPull = { fleet.gitPull(sessionId) },
                gitPush = { fleet.gitPush(sessionId) },
                gitPublish = { fleet.gitPublish(sessionId) },
            ),
            loadModels = { fleet.sessionModels(sessionId) },
            loadReasoning = { fleet.sessionReasoning(sessionId) },
            pickModel = { fleet.switchModel(sessionId, it) },
            pickReasoning = { fleet.switchReasoning(sessionId, it) },
            ensureMessagesLoaded = { fleet.ensureMessagesLoaded(sessionId) },
            loadProxies = { fleet.proxies() },
            respondRequest = { requestId, answer -> fleet.respondRequest(sessionId, requestId, answer) },
            setPermissionMode = { fleet.setPermissionMode(sessionId, it) },
        )
    }
