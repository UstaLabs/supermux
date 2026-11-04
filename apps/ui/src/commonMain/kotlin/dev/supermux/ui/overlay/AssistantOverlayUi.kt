// The assistant overlay's look (#62): the default Agent as a living avatar over other apps, the speech
// bubble its replies type out in, the hints while you hold it to talk, the ✕ you drop it on to hide it,
// and its chat (a sheet on a phone, a card on a wide screen). Each platform hosts these in its own
// windows (Android: overlay windows owned by a service); the windows share one [OverlayUiState].
package dev.supermux.ui.overlay

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.supermux.chat.parseChatTs
import dev.supermux.proto.SessionInfo
import dev.supermux.session.sessionListShowsUnread
import dev.supermux.state.FleetStore
import dev.supermux.ui.chat.ChatPanel
import dev.supermux.ui.chat.rememberChatActions
import dev.supermux.ui.chat.rememberChatState
import dev.supermux.ui.chat.rememberDictation
import dev.supermux.ui.platform.LocalPlatform
import dev.supermux.ui.session.AGENT_DOZE_AFTER_MS
import dev.supermux.ui.session.AgentAvatar
import dev.supermux.ui.session.AvatarRig
import dev.supermux.ui.session.AvatarRigView
import dev.supermux.ui.session.rememberAgentAvatars
import dev.supermux.ui.sessionPreviewPlainText
import kotlinx.coroutines.delay
import kotlin.time.Clock

/** The default Agent as the overlay shows it. */
@Immutable
data class OverlayAgent(
    val id: String,
    val name: String,
    val online: Boolean,
    /** Its host is connected, so a reply reaches the overlay live (not only as a push). */
    val connected: Boolean,
    val working: Boolean,
    val unread: Boolean,
    val lastTs: String?,
    val session: SessionInfo,
)

/** The default Agent ("Make default" picks it), or the first Agent when none is marked; null = none. */
@Composable
fun rememberOverlayAgent(fleet: FleetStore): OverlayAgent? {
    val sessions by fleet.sessions.collectAsState()
    val agentState by fleet.agentState.collectAsState()
    val messages by fleet.messages.collectAsState()
    val lastRead by fleet.lastRead.collectAsState()
    val hostViews by fleet.hostViews.collectAsState()
    val sessionHost by fleet.sessionHost.collectAsState()
    val s = sessions.firstOrNull { it.role == "personal_assistant" && it.isDefault == true }
        ?: sessions.firstOrNull { it.role == "personal_assistant" }
        ?: return null
    LaunchedEffect(s.id) { fleet.ensureMessagesLoaded(s.id) }
    val working = agentState[s.id]?.busy == true
    val lastTs = messages[s.id]?.lastOrNull()?.ts
    val hostOnline = sessionHost[s.id]?.let { h -> hostViews.firstOrNull { it.recordId == h }?.online } ?: true
    return OverlayAgent(
        id = s.id,
        name = s.name,
        online = hostOnline && (s.status == null || s.status == "active"),
        connected = hostOnline,
        working = working,
        unread = sessionListShowsUnread(active = false, working = working, lastMessageTs = lastTs, lastReadAt = lastRead[s.id]),
        lastTs = lastTs,
        session = s,
    )
}

/** The Agent's avatar rig, when it has one (else the overlay draws its name-derived look). */
@Composable
fun rememberOverlayRig(fleet: FleetStore, agentId: String?): AvatarRig? {
    val sessions by fleet.sessions.collectAsState()
    val rigs = rememberAgentAvatars(sessions, apiFor = { fleet.appFor(it)?.api })
    return agentId?.let { rigs[it] }
}

/** A reply typing out in the bubble. */
@Immutable
data class OverlayReply(val id: String, val from: String, val text: String)

/** The talk controls the hold gesture drives; installed by [OverlayVoice]. */
class OverlayVoiceControl(val start: () -> Unit, val send: () -> Unit, val cancel: () -> Unit)

/** What the overlay's windows share: each window is its own composition, all read and write this. */
@Stable
class OverlayUiState {
    /** Holding it to talk (or locked, talking hands-free). */
    var listening by mutableStateOf(false)
    var locked by mutableStateOf(false)
    var cancelArmed by mutableStateOf(false)
    var lockArmed by mutableStateOf(false)
    /** The words heard so far. */
    var heard by mutableStateOf("")
    /** The live voice level 0..1 the avatar's mouth follows. */
    var level by mutableFloatStateOf(0f)
    /** Talking stopped; waiting for the last words before sending. */
    var finishing by mutableStateOf(false)
    /** No mic: say so instead of listening. */
    var micMissing by mutableStateOf(false)
    var reply by mutableStateOf<OverlayReply?>(null)
    var replyShown by mutableIntStateOf(0)
    var replyFolded by mutableStateOf(false)
    var pressed by mutableStateOf(false)
    var chatOpen by mutableStateOf(false)
    var dragging by mutableStateOf(false)
    var dropHot by mutableStateOf(false)
    var edge by mutableStateOf(Edge.Left)
    /** Dropped on the ✕: out of sight until its next reply. */
    var hidden by mutableStateOf(false)
    var voice: OverlayVoiceControl? = null

    val speaking: Boolean get() = reply?.let { replyShown < it.text.length } == true

    /** Anything for the bubble to show right now. */
    val bubbleVisible: Boolean
        get() = !hidden && !dragging && !chatOpen && (listening || finishing || micMissing || (reply != null && !replyFolded))
}

/** The longest stretch of a reply the bubble types out; the rest is one tap away. */
private const val BUBBLE_CHARS = 150

/**
 * Pops the bubble when the Agent replies (unless its chat is open), types it out while the avatar
 * speaks, then folds it into the count after a while. Hosted once, in the head's composition.
 */
@Composable
fun OverlayReplies(fleet: FleetStore, agent: OverlayAgent?, ui: OverlayUiState) {
    val id = agent?.id ?: return
    LaunchedEffect(id) {
        fleet.agentReplies.collect { ev ->
            if (ev.session != id || ui.chatOpen) return@collect
            val text = sessionPreviewPlainText(ev.entry.text.orEmpty()).trim()
            if (text.isEmpty()) return@collect
            ui.reply = OverlayReply(ev.entry.id, agent.name, text)
            ui.replyShown = 0
            ui.replyFolded = false
        }
    }
    val reply = ui.reply
    LaunchedEffect(reply?.id) {
        val r = reply ?: return@LaunchedEffect
        val shown = minOf(r.text.length, BUBBLE_CHARS)
        while (ui.replyShown < shown) {
            delay(40)
            ui.replyShown = minOf(shown, ui.replyShown + 3)
        }
        ui.replyShown = r.text.length
        delay(7_000)
        ui.replyFolded = true
    }
}

/**
 * Hold-to-talk: the composer's own dictation, so the overlay hears exactly as the chat's mic does. When
 * the talking stops, the words go to the Agent as a message.
 */
@Composable
fun OverlayVoice(fleet: FleetStore, agentId: String, ui: OverlayUiState) {
    val actions = rememberChatActions(fleet, agentId)
    var latest by remember { mutableStateOf("") }
    val mic = LocalPlatform.current.mic
    val dictation = rememberDictation(
        resetKey = agentId,
        loadGlossary = actions.composer.loadGlossary,
        transcribeDraft = actions.composer.transcribeDraft,
        transcribeAudio = { bytes, name, mime -> actions.transcribeAudio?.invoke(bytes, name, mime) },
        onAppend = { latest = it; ui.heard = it },
        mic = mic,
        transcribePartial = actions.composer.transcribePartial,
        loadVoiceCaps = actions.composer.loadVoiceCaps,
        checkEdit = actions.composer.checkEdit,
        transcribeWarm = actions.composer.transcribeWarm,
        openDictationStream = actions.composer.openDictationStream,
    )
    dictation.onLive = { latest = it; ui.heard = it }
    dictation.currentText = { latest }
    LaunchedEffect(mic, ui.listening) {
        if (!ui.listening) { ui.level = 0f; return@LaunchedEffect }
        mic.level?.collect { ui.level = voiceLevel(it) }
    }
    LaunchedEffect(ui.finishing, dictation.active, dictation.transcribing) {
        if (ui.finishing && !dictation.active && !dictation.transcribing) {
            val text = latest.trim()
            ui.finishing = false
            ui.heard = ""
            latest = ""
            if (text.isNotEmpty()) fleet.sendMessage(agentId, text)
        }
    }
    LaunchedEffect(ui.micMissing) {
        if (ui.micMissing) { delay(3_500); ui.micMissing = false }
    }
    DisposableEffect(dictation) {
        ui.voice = OverlayVoiceControl(
            start = {
                latest = ""
                ui.heard = ""
                // There is no permission prompt over other apps: the mic is allowed in supermux.
                if (mic.available) dictation.startMic() else ui.micMissing = true
            },
            send = { ui.finishing = true; dictation.stopMic() },
            cancel = { dictation.cancelMic(); ui.heard = ""; latest = "" },
        )
        onDispose { ui.voice = null }
    }
    LaunchedEffect(dictation.micDenied, dictation.micUnavailable) {
        if (dictation.micDenied || dictation.micUnavailable) {
            ui.micMissing = true
            ui.listening = false
            ui.finishing = false
        }
    }
}

/** The living avatar on its glass circle, with its count and the ring that shows it listening. */
@Composable
fun OverlayHead(agent: OverlayAgent?, rig: AvatarRig?, ui: OverlayUiState, size: Dp, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    var now by remember { mutableLongStateOf(Clock.System.now().toEpochMilliseconds()) }
    val dozeAt = parseChatTs(agent?.lastTs)?.plus(AGENT_DOZE_AFTER_MS)
    LaunchedEffect(dozeAt) {
        val at = dozeAt ?: return@LaunchedEffect
        delay((at - Clock.System.now().toEpochMilliseconds()).coerceAtLeast(0))
        now = Clock.System.now().toEpochMilliseconds()
    }
    val dozing = dozeAt == null || now >= dozeAt
    val state = overlayAvatarState(
        listening = ui.listening,
        speaking = ui.speaking,
        online = agent?.online ?: false,
        working = agent?.working ?: false,
        unread = agent?.unread ?: false,
        dozing = dozing,
        awake = ui.pressed || ui.chatOpen,
    )
    val pulse = rememberInfiniteTransition(label = "overlay-listen")
    val ring by pulse.animateFloat(0.92f, 1.06f, infiniteRepeatable(tween(550), RepeatMode.Reverse), label = "ring")
    val lift by animateFloatAsState(if (ui.pressed || ui.listening) 1.08f else 1f, label = "lift")
    Box(modifier.size(size + 8.dp).testTag("overlay_head"), contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(size).scale(lift).shadow(10.dp, CircleShape).clip(CircleShape)
                .background(cs.surfaceContainerHigh.copy(alpha = 0.78f))
                .border(1.dp, cs.outlineVariant.copy(alpha = 0.5f), CircleShape),
        )
        if (ui.listening) {
            Box(
                Modifier.size(size).scale(ring)
                    .border(3.dp, (if (ui.cancelArmed) cs.error else cs.primary).copy(alpha = 0.7f), CircleShape),
            )
        }
        val inner = (size.value * 0.82f).toInt()
        if (rig != null) {
            AvatarRigView(rig, state = state, hovered = ui.pressed || ui.chatOpen, level = ui.level, seed = agent?.id ?: "", size = inner)
        } else if (agent != null) {
            AgentAvatar(
                agent.name,
                online = state != "asleep",
                working = state == "working",
                unread = state == "waiting",
                hovered = ui.pressed || ui.listening || ui.chatOpen,
                size = inner,
            )
        }
        if (agent?.unread == true && !ui.chatOpen && !ui.listening && !ui.speaking) {
            Box(
                Modifier.align(Alignment.TopEnd).offset(x = (-2).dp, y = 2.dp).size(18.dp).clip(CircleShape)
                    .background(cs.primary).border(2.dp, cs.surface, CircleShape),
                contentAlignment = Alignment.Center,
            ) { Text("1", color = cs.onPrimary, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold) }
        }
    }
}

/**
 * The bubble above the head: the reply typing out, the words you are saying (with how to lock or
 * cancel), or the lock's Send / Cancel. [onOpen] opens the chat; [onSendLocked]/[onCancelLocked] end a
 * locked recording.
 */
@Composable
fun OverlayBubble(
    ui: OverlayUiState,
    onOpen: () -> Unit,
    onSendLocked: () -> Unit,
    onCancelLocked: () -> Unit,
    maxWidth: Dp,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val right = ui.edge == Edge.Right
    Column(
        modifier.widthIn(max = maxWidth).padding(4.dp).testTag("overlay_bubble"),
        horizontalAlignment = if (right) Alignment.End else Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val shape = RoundedCornerShape(
            topStart = 18.dp, topEnd = 18.dp,
            bottomStart = if (right) 18.dp else 6.dp, bottomEnd = if (right) 6.dp else 18.dp,
        )
        when {
            ui.micMissing -> BubbleBox(shape, cs.surfaceContainerHigh) {
                Text("Allow the microphone in supermux to talk to it here.", color = cs.onSurface, style = MaterialTheme.typography.bodyMedium)
            }
            ui.listening -> {
                BubbleBox(shape, cs.primaryContainer) {
                    Text(
                        ui.heard.ifBlank { "Listening…" },
                        color = cs.onPrimaryContainer,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (ui.locked) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onCancelLocked, modifier = Modifier.testTag("overlay_lock_cancel")) { Text("Cancel") }
                        Button(onClick = onSendLocked, modifier = Modifier.testTag("overlay_lock_send")) { Text("Send") }
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Hint(if (ui.lockArmed) cs.primary else cs.surfaceContainerHigh, if (ui.lockArmed) cs.onPrimary else cs.onSurfaceVariant) {
                            Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.size(14.dp))
                            Icon(Icons.Filled.KeyboardArrowUp, contentDescription = null, modifier = Modifier.size(14.dp))
                        }
                        Hint(if (ui.cancelArmed) cs.error else cs.surfaceContainerHigh, if (ui.cancelArmed) cs.onError else cs.onSurfaceVariant) {
                            Text(
                                when {
                                    ui.cancelArmed -> "Release to cancel"
                                    right -> "‹ Slide to cancel"
                                    else -> "Slide to cancel ›"
                                },
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                }
            }
            ui.finishing -> BubbleBox(shape, cs.primaryContainer) {
                Text(ui.heard.ifBlank { "…" }, color = cs.onPrimaryContainer, style = MaterialTheme.typography.bodyMedium, maxLines = 4, overflow = TextOverflow.Ellipsis)
            }
            ui.reply != null -> {
                val r = ui.reply!!
                val cut = r.text.length > BUBBLE_CHARS
                BubbleBox(shape, cs.surfaceContainerHigh, Modifier.clickable(onClick = onOpen)) {
                    Text(r.from, color = cs.onSurfaceVariant, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        r.text.take(ui.replyShown.coerceAtMost(BUBBLE_CHARS)) + if (cut && !ui.speaking) "…" else "",
                        color = cs.onSurface,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (cut && !ui.speaking) {
                        Text("Read it all", color = cs.primary, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun BubbleBox(shape: RoundedCornerShape, color: androidx.compose.ui.graphics.Color, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(shape = shape, color = color, shadowElevation = 6.dp, modifier = modifier) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) { content() }
    }
}

@Composable
private fun Hint(bg: androidx.compose.ui.graphics.Color, fg: androidx.compose.ui.graphics.Color, content: @Composable () -> Unit) {
    Surface(shape = RoundedCornerShape(50), color = bg, contentColor = fg, shadowElevation = 3.dp) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) { content() }
    }
}

/** The ✕ a dragged head is dropped on to hide it. */
@Composable
fun OverlayDropTarget(hot: Boolean, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val grow by animateFloatAsState(if (hot) 1.18f else 1f, label = "drop")
    Box(
        modifier.size(64.dp).scale(grow).shadow(8.dp, CircleShape).clip(CircleShape)
            .background(if (hot) cs.error else cs.inverseSurface.copy(alpha = 0.82f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.Close, contentDescription = "Hide", tint = if (hot) cs.onError else cs.inverseOnSurface)
    }
}

/**
 * The Agent's chat over the app you are in: its recent messages and the composer, exactly the chat
 * panel the app shows. [compact] draws it as a bottom sheet with the avatar riding on its edge (a phone);
 * otherwise as a card (a wide screen). [onOpenInApp] opens the full chat in supermux.
 */
@Composable
fun OverlayChat(
    fleet: FleetStore,
    agent: OverlayAgent,
    rig: AvatarRig?,
    ui: OverlayUiState,
    compact: Boolean,
    onClose: () -> Unit,
    onOpenInApp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val state = rememberChatState(fleet, agent.id)
    val actions = rememberChatActions(fleet, agent.id)
    var draft by remember(agent.id) { mutableStateOf("") }
    LaunchedEffect(agent.id) {
        fleet.markRead(agent.id)
        fleet.markReadOnServer(listOf(agent.id))
    }
    val shape = if (compact) RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp) else RoundedCornerShape(24.dp)
    val headSize = 52.dp
    Box(modifier.testTag("overlay_chat")) {
        Surface(
            shape = shape,
            color = cs.surfaceContainerLow,
            shadowElevation = 12.dp,
            modifier = Modifier.fillMaxSize().padding(top = if (compact) headSize * 0.55f else 0.dp),
        ) {
            Column(Modifier.fillMaxSize()) {
                if (compact) {
                    Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
                        Box(Modifier.size(width = 36.dp, height = 4.dp).clip(RoundedCornerShape(2.dp)).background(cs.outlineVariant))
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(start = if (compact) headSize + 22.dp else 16.dp, end = 4.dp, top = 6.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(agent.name, style = MaterialTheme.typography.titleMedium, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            when {
                                agent.working -> "working…"
                                !agent.online -> "asleep · wakes up when you write"
                                else -> "Always on"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (agent.working) cs.primary else cs.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = onOpenInApp, modifier = Modifier.testTag("overlay_open_in_app")) {
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = "Open in supermux", tint = cs.onSurfaceVariant)
                    }
                    IconButton(onClick = onClose, modifier = Modifier.testTag("overlay_close")) {
                        Icon(Icons.Filled.Close, contentDescription = "Close", tint = cs.onSurfaceVariant)
                    }
                }
                ChatPanel(
                    session = agent.session,
                    state = state,
                    actions = actions,
                    draft = draft,
                    onDraftChange = { draft = it },
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    showHeader = false,
                )
            }
        }
        if (compact) {
            // It rides on the sheet's edge: the same avatar, now awake and attentive.
            Box(Modifier.padding(start = 18.dp).size(headSize)) {
                OverlayHead(agent, rig, ui, size = headSize - 8.dp)
            }
        }
    }
}

/** A scrim behind the phone sheet; tapping it closes the chat. */
@Composable
fun OverlayScrim(onTap: () -> Unit, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Box(modifier.fillMaxSize().background(cs.scrim.copy(alpha = 0.32f)).clickable(onClick = onTap))
}
