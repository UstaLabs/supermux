// The assistant overlay's look (#62): the default Agent as a living avatar over other apps, the speech
// bubble its replies type out in, the hints while you hold it to talk, the ✕ you drop it on to hide it,
// and its chat (a sheet on a phone, a card on a wide screen). Each platform hosts these in its own
// windows (Android: overlay windows owned by a service); the windows share one [OverlayUiState].
package dev.supermux.ui.overlay

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.supermux.chat.liveMarkdown
import dev.supermux.chat.parseChatTs
import dev.supermux.proto.SessionInfo
import dev.supermux.session.sessionListShowsUnread
import dev.supermux.session.sessionsByUserOrder
import dev.supermux.state.FleetStore
import dev.supermux.state.LiveReply
import dev.supermux.ui.chat.ChatPanel
import dev.supermux.ui.chat.InkBlock
import dev.supermux.ui.chat.LiveRevealState
import dev.supermux.ui.chat.RevealDriver
import dev.supermux.ui.chat.appendLinkified
import dev.supermux.ui.chat.inkEdge
import dev.supermux.ui.chat.rememberChatActions
import dev.supermux.ui.chat.rememberChatState
import dev.supermux.ui.chat.rememberDictation
import dev.supermux.ui.platform.LocalPlatform
import dev.supermux.ui.platform.MicTrouble
import dev.supermux.ui.session.AGENT_DOZE_AFTER_MS
import dev.supermux.ui.session.AgentAvatar
import dev.supermux.ui.session.AvatarRig
import dev.supermux.ui.session.AvatarRigView
import dev.supermux.ui.session.rememberAgentAvatars
import dev.supermux.ui.session.rememberAvatarClock
import dev.supermux.ui.session.wearingGlasses
import dev.supermux.ui.PlainMessage
import dev.supermux.ui.messagePlainText
import dev.supermux.ui.messagePlainLinked
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
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

/**
 * The Agent the overlay shows: [chosenId] (picked on this device in its chat's header) while it exists,
 * else the default Agent ("Make default" picks it), else the first; null = none, or [ready] is false
 * (the device's choice isn't read yet, so it doesn't flash the default first).
 */
@Composable
fun rememberOverlayAgent(fleet: FleetStore, chosenId: String? = null, ready: Boolean = true): OverlayAgent? {
    val sessions by fleet.sessions.collectAsState()
    val agentState by fleet.agentState.collectAsState()
    val messages by fleet.messages.collectAsState()
    val lastRead by fleet.lastRead.collectAsState()
    val hostViews by fleet.hostViews.collectAsState()
    val sessionHost by fleet.sessionHost.collectAsState()
    if (!ready) return null
    val s = pickOverlayAgent(sessions.filter { it.role == "personal_assistant" }, chosenId, { it.id }, { it.isDefault == true })
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

/**
 * The reply on show: its [text] as the Agent wrote it (Markdown), the live [stream] it came from (null: nobody saw it
 * being written), and whether it is [done] (stored) or still being written.
 */
@Immutable
data class OverlayReply(val id: String, val from: String, val text: String, val stream: String? = null, val done: Boolean = true)

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
    /** The voice level 0..1 the avatar moves with: [levelTarget], smoothed (see [smoothLevel]). */
    var level by mutableFloatStateOf(0f)
    /** The voice right now: yours while listening, the reply's stand-in while it is written. */
    var levelTarget by mutableFloatStateOf(0f)
    /** Talking stopped; waiting for the last words before sending. */
    var finishing by mutableStateOf(false)
    /** Held to talk while the last talk is still [finishing]: the mic opens once that one has its words. */
    var micWaiting by mutableStateOf(false)
    /** Your words were sent and the answer hasn't come yet: the avatar works meanwhile. */
    var thinking by mutableStateOf(false)
    /** The mic didn't open: what the bubble says instead of listening, as the OS told why ([micTroubleNote]). */
    var micNote by mutableStateOf<String?>(null)
    /** No mic to listen with right now: [micNote] says why. */
    val micMissing: Boolean get() = micNote != null
    var reply by mutableStateOf<OverlayReply?>(null)
    /** The reply's ink-edge reveal while it is written (#76's, as the chat's live row has): null when it shows as it is. */
    internal var reveal by mutableStateOf<LiveRevealState?>(null)
    var replyFolded by mutableStateOf(false)
    /** "Read it all": the reply shows whole, as tall as [bubbleRoom] lets it; it doesn't fold away meanwhile. */
    var replyExpanded by mutableStateOf(false)
    /** How tall the bubble may grow where it is now, between the head and the screen's edge (set by the host). */
    var bubbleRoom by mutableStateOf(320.dp)
    var pressed by mutableStateOf(false)
    var chatOpen by mutableStateOf(false)
    var dragging by mutableStateOf(false)
    var dropHot by mutableStateOf(false)
    var edge by mutableStateOf(Edge.Left)
    /** Dropped on the ✕: out of sight until its next reply. */
    var hidden by mutableStateOf(false)
    /** A picture of the screen is being taken: nothing of the overlay may be in it. */
    var capturing by mutableStateOf(false)
    /** This device's switch in Settings: the Agent may see your screen at all (a double-tap puts its glasses on). */
    var screenWithVoice by mutableStateOf(true)
    /**
     * Its glasses are on: Android's screen share is open (paused between talks), and your screen goes with
     * every hold-to-talk. They come off with the share: a double-tap, the screen going off, idle minutes.
     */
    var screenSharing by mutableStateOf(false)
    /** The screen taken when you held it to talk, going with your words unless you drop it. */
    var shot by mutableStateOf<OverlayShot?>(null)
    /** Pictures of the screen taken so far: each one glints across the head. */
    var shots by mutableIntStateOf(0)
    /** The screen is being taken this moment: the bubble waits, so it isn't in the picture. */
    var snapping by mutableStateOf(false)
    /** The phone's screen is on; while it's off nobody sees the head, so it doesn't draw. */
    var screenOn by mutableStateOf(true)
    var voice: OverlayVoiceControl? = null

    /** The reply is still being written or revealed: the head speaks meanwhile. */
    val speaking: Boolean get() = reveal?.let { !it.finished } == true

    /** Anything for the bubble to show right now. */
    val bubbleVisible: Boolean
        get() = !hidden && !capturing && !snapping && !dragging && !chatOpen &&
            (listening || finishing || micMissing || (reply != null && !replyFolded))
}

/** The screen taken when you held the overlay to talk: sent with your words unless you drop it. */
@Stable
class OverlayShot(val thumb: ImageBitmap, val png: Deferred<ByteArray?>) {
    var dropped by mutableStateOf(false)
}

/** The longest stretch of a reply the bubble shows; the rest is one tap away. */
private const val BUBBLE_CHARS = 150

/** How long it works after you talked before giving up on an answer. */
private const val THINKING_MAX_MS = 90_000L

/**
 * Pops the bubble when the Agent replies (unless its chat is open) and shows the reply as it is actually written: its
 * live text under the chat's own ink-edge reveal (#76), which the stored reply then finishes. A reply nobody saw being
 * written (live replies off in Settings, an older broker) shows as it is, at once. Shown whole, it folds into the count
 * after a while (not while you read it all). It asks for its Agent's live text without claiming the chat is on screen.
 * Hosted once, in the head's composition.
 */
@Composable
fun OverlayReplies(fleet: FleetStore, agent: OverlayAgent?, ui: OverlayUiState) {
    val id = agent?.id ?: return
    // Live text without presence: nothing is read and pushes still go out (FleetStore.pinLive).
    DisposableEffect(fleet, id) {
        fleet.pinLive(setOf(id))
        onDispose { fleet.pinLive(emptySet()) }
    }
    val live by (remember(fleet, id) { fleet.appFor(id) }?.liveReplies ?: NoLiveReplies).collectAsState()
    val writing = live[id]?.lastOrNull { !it.thinking }
    // A reply being written: the bubble shows it, and follows it as more is written.
    LaunchedEffect(writing?.stream, writing?.text) {
        val w = writing ?: return@LaunchedEffect
        if (ui.chatOpen) return@LaunchedEffect
        val shown = ui.reply
        if (shown?.stream == w.stream) {
            if (!shown.done) ui.reply = shown.copy(text = w.text)
            return@LaunchedEffect
        }
        ui.reveal = LiveRevealState()
        ui.reply = OverlayReply(w.stream, agent.name, w.text, stream = w.stream, done = false)
        ui.replyFolded = false
        ui.replyExpanded = false
        ui.thinking = false
    }
    // A stored reply finishes the reveal of the stream it replaces; one nobody saw being written shows as it is.
    LaunchedEffect(id) {
        fleet.agentReplies.collect { ev ->
            if (ev.session != id || ui.chatOpen) return@collect
            val text = ev.entry.text.orEmpty()
            if (messagePlainText(text).isEmpty()) return@collect
            val continues = ev.entry.stream != null && ui.reply?.stream == ev.entry.stream && ui.reveal?.finished == false
            if (!continues) {
                ui.reveal = null
                ui.replyFolded = false
                ui.replyExpanded = false
            }
            ui.reply = OverlayReply(ev.entry.id, agent.name, text, stream = ev.entry.stream, done = true)
            ui.thinking = false
        }
    }
    val reply = ui.reply
    val reveal = ui.reveal
    // The reveal's clock runs here, whichever window shows the bubble.
    if (reply != null && reveal != null) RevealDriver(reveal, reply.text, done = reply.done)
    // Its mouth moves with the words as they appear.
    LaunchedEffect(reveal) {
        if (reveal == null) {
            ui.levelTarget = 0f
            return@LaunchedEffect
        }
        var from = 0
        snapshotFlow { reveal.laidOut }.collect { to ->
            val text = ui.reply?.text.orEmpty()
            ui.levelTarget = if (to in (from + 1)..text.length) speechLevel(text.subSequence(from, to)) else 0f
            from = to
        }
    }
    // Shown whole, it folds into the count after a while, unless you are reading it all.
    val whole = reply != null && reply.done && (reveal == null || reveal.finished)
    LaunchedEffect(reply?.id, whole, ui.replyExpanded) {
        if (!whole || ui.replyExpanded) return@LaunchedEffect
        ui.levelTarget = 0f
        delay(7_000)
        ui.replyFolded = true
    }
}

/** A host not known yet has nothing being written. */
private val NoLiveReplies: StateFlow<Map<String, List<LiveReply>>> = MutableStateFlow(emptyMap())

/**
 * Hold-to-talk: the composer's own dictation, so the overlay hears exactly as the chat's mic does. When
 * the talking stops, the words go to the Agent as a message.
 */
@Composable
fun OverlayVoice(fleet: FleetStore, agentId: String, ui: OverlayUiState) {
    val actions = rememberChatActions(fleet, agentId)
    // Where a finished talk is sent from: lives as long as the overlay's voice, not one effect run.
    val talks = rememberCoroutineScope()
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
    // A talk held while the last is still being transcribed waits for those words (the dictation opens no
    // mic until then), and each talk keeps its own picture of the screen.
    val turns = remember { TalkTurns() }
    var talkShot by remember { mutableStateOf<OverlayShot?>(null) }
    // Asking the OS why the mic didn't open may take it a moment (a desktop asks its sound system): not on a frame.
    val asking = rememberCoroutineScope()
    /**
     * The mic didn't open: it stops listening (no ring, no voice to follow) and says why, as the OS tells it, never
     * asking to allow what can't be allowed.
     */
    fun tellMicTrouble(denied: Boolean) {
        ui.listening = false
        ui.finishing = false
        asking.launch {
            val trouble = if (denied) MicTrouble.NOT_ALLOWED else mic.trouble()
            ui.micNote = micTroubleNote(trouble, fleet.sessions.value.firstOrNull { it.id == agentId }?.name ?: "your Agent")
        }
    }
    fun listen() {
        latest = ""
        ui.heard = ""
        // There is no permission prompt over other apps: the mic is allowed in supermux.
        if (!mic.available) return tellMicTrouble(denied = false)
        dictation.startMic()
        // Read now, not by an effect: a try that fails as the last one did leaves the flag as it was, and no effect
        // would see it rise.
        if (dictation.micUnavailable) tellMicTrouble(denied = false)
    }
    LaunchedEffect(mic, ui.listening) {
        if (!ui.listening) { ui.levelTarget = 0f; return@LaunchedEffect }
        // Recording for the broker meters the mic; the on-device recogniser meters itself.
        val recorded = mic.level?.map { voiceLevel(it) } ?: emptyFlow()
        val recognised = mic.liveTranscript?.level ?: emptyFlow()
        merge(recorded, recognised).collect { ui.levelTarget = it }
    }
    // The shown level follows the voice, frame by frame, only while there is a voice to follow.
    // Derived: a reply typing out changes `speaking`'s inputs every few frames, not its value.
    val voiced by remember(ui) { derivedStateOf { ui.listening || ui.speaking } }
    LaunchedEffect(voiced) {
        var last = withFrameMillis { it }
        while (voiced || ui.level > 0.001f) {
            withFrameMillis { now ->
                ui.level = smoothLevel(ui.level, if (voiced) ui.levelTarget else 0f, (now - last).toFloat())
                last = now
            }
        }
        ui.level = 0f
    }
    LaunchedEffect(ui.finishing, dictation.active, dictation.transcribing) {
        if (ui.finishing && !dictation.active && !dictation.transcribing) {
            val text = latest.trim()
            val shot = talkShot?.takeUnless { it.dropped }
            ui.finishing = false
            ui.heard = ""
            // Only its own picture: a talk held meanwhile may have taken the next one.
            if (ui.shot === talkShot) ui.shot = null
            talkShot = null
            latest = ""
            if (text.isNotEmpty()) {
                ui.thinking = true
                // The screen goes up only now that you've let go: a cancelled talk never leaves the phone.
                // Not in this effect: it restarts as soon as `finishing` flips, which cancelled the upload.
                finishTalk(
                    talks, text, "screen-${Clock.System.now().toEpochMilliseconds()}.png", shot?.png,
                    actions.upload, actions.send,
                ) { println("[OverlayTalk] $it") }
            } else {
                println("[OverlayTalk] nothing was heard: nothing sent" + if (shot != null) " (the screen is dropped too)" else "")
            }
            // A talk held meanwhile has waited for these words: now it listens.
            if (turns.lastFinished()) {
                ui.micWaiting = false
                if (ui.listening) listen()
            }
        }
    }
    LaunchedEffect(ui.thinking) {
        // An answer that never comes (a failed send, a reply with no text) must not leave it working.
        if (ui.thinking) { delay(THINKING_MAX_MS); ui.thinking = false }
    }
    LaunchedEffect(ui.micNote) {
        if (ui.micNote != null) { delay(3_500); ui.micNote = null }
    }
    DisposableEffect(dictation) {
        ui.voice = OverlayVoiceControl(
            start = { if (turns.start(finishing = ui.finishing)) listen() else ui.micWaiting = true },
            send = {
                if (turns.end()) {
                    // Let go of before the mic was its own: nothing to send, and its picture goes.
                    ui.micWaiting = false
                    ui.shot = null
                } else {
                    talkShot = ui.shot
                    ui.finishing = true
                    dictation.stopMic()
                }
            },
            cancel = {
                if (turns.end()) {
                    ui.micWaiting = false
                } else {
                    dictation.cancelMic()
                    ui.heard = ""
                    latest = ""
                }
            },
        )
        onDispose { ui.voice = null }
    }
    LaunchedEffect(dictation.micDenied) {
        if (dictation.micDenied) tellMicTrouble(denied = true)
    }
}

/** The living avatar on its glass circle, with its count, the ring that shows it listening, and its glasses. */
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
    // Its glasses are on while it can see your screen; it doesn't doze off in them.
    val glasses = ui.screenSharing
    val state = overlayAvatarState(
        listening = ui.listening,
        speaking = ui.speaking,
        thinking = ui.finishing || ui.thinking,
        online = agent?.online ?: false,
        working = agent?.working ?: false,
        unread = agent?.unread ?: false,
        dozing = dozing,
        awake = ui.pressed || ui.chatOpen || glasses,
    )
    // Its own clock, only as fast as it needs: smooth while you touch it, talk to it or it speaks,
    // slower while idle, slowest asleep, still while hidden. The avatar draws at this rate instead of
    // on every screen refresh, which is what an always-on overlay costs in battery.
    val fps = when {
        ui.hidden || ui.capturing || !ui.screenOn -> 0
        ui.pressed || ui.listening || ui.speaking || ui.dragging -> 60
        state == "asleep" -> 8
        else -> 24
    }
    val clock = rememberAvatarClock(fps)
    val lift by animateFloatAsState(if (ui.pressed || ui.listening) 1.08f else 1f, label = "lift")
    val worn = remember(rig) { rig?.wearingGlasses() }
    // Just after a picture of the screen is taken, a glint sweeps across it.
    val glint = remember { Animatable(1f) }
    LaunchedEffect(ui.shots) {
        if (ui.shots == 0) return@LaunchedEffect
        glint.snapTo(0f)
        glint.animateTo(1f, tween(560, easing = FastOutSlowInEasing))
    }
    Box(modifier.size(size + 8.dp).testTag("overlay_head"), contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(size).scale(lift).shadow(10.dp, CircleShape).clip(CircleShape)
                .background(cs.surfaceContainerHigh.copy(alpha = 0.78f))
                .border(
                    if (glasses) 2.dp else 1.dp,
                    if (glasses) cs.primary.copy(alpha = 0.7f) else cs.outlineVariant.copy(alpha = 0.5f),
                    CircleShape,
                ),
        )
        if (ui.listening) {
            val pulse = rememberInfiniteTransition(label = "overlay-listen")
            val ring by pulse.animateFloat(0.92f, 1.06f, infiniteRepeatable(tween(550), RepeatMode.Reverse), label = "ring")
            Box(
                Modifier.size(size).scale(ring)
                    .border(3.dp, (if (ui.cancelArmed) cs.error else cs.primary).copy(alpha = 0.7f), CircleShape),
            )
        }
        val inner = (size.value * 0.82f).toInt()
        // A press perks it up, but not while you hold it to talk: then it is listening, not hovered.
        val hovered = (ui.pressed && !ui.listening) || ui.chatOpen
        // In its glasses, its eyes read your screen, toward the middle of it.
        val gaze = if (glasses && (state == "idle" || state == "waiting")) readingGaze(clock, if (ui.edge == Edge.Left) 1 else -1) else null
        if (rig != null) {
            AvatarRigView(
                if (glasses) worn ?: rig else rig,
                state = state,
                hovered = hovered,
                pointer = gaze?.let { Offset(it.first, it.second) },
                level = ui.level,
                seed = agent?.id ?: "",
                size = inner,
                fixedTime = clock,
            )
        } else if (agent != null) {
            // No avatar of its own: its name-derived one, with the same states.
            AgentAvatar(
                agent.name,
                online = state != "asleep",
                working = state == "working",
                unread = state == "waiting",
                hovered = hovered,
                size = inner,
                listening = state == "listening",
                speaking = state == "speaking",
                level = ui.level,
                timeMs = clock,
                blinking = fps > 0,
                glasses = glasses,
                gaze = gaze,
            )
        }
        // An avatar its glasses can't be fitted to wears a badge for them instead.
        if (glasses && rig != null && worn == null) {
            Box(
                Modifier.align(Alignment.BottomEnd).offset(x = (-2).dp, y = (-2).dp).size(18.dp).clip(CircleShape)
                    .background(cs.primary).border(2.dp, cs.surface, CircleShape),
                contentAlignment = Alignment.Center,
            ) { GlassesGlyph(cs.onPrimary, Modifier.size(12.dp)) }
        }
        if (glint.value < 1f) {
            Box(Modifier.size(size).scale(lift).clip(CircleShape).drawBehind { drawGlint(glint.value) })
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

/** A white band sweeping across the head at [p] (0 to 1): the cue that a picture of the screen was taken. */
private fun DrawScope.drawGlint(p: Float) {
    val w = size.width
    val x = -0.4f * w + p * 1.8f * w
    rotate(20f) {
        drawRect(
            Brush.horizontalGradient(
                listOf(Color.Transparent, Color.White.copy(alpha = 0.8f), Color.Transparent),
                startX = x - w * 0.2f,
                endX = x + w * 0.2f,
            ),
            topLeft = Offset(x - w * 0.2f, -w * 0.5f),
            size = Size(w * 0.4f, w * 2f),
        )
    }
}

/** A small pair of round glasses: what the overlay's Agent wears while it can see your screen. */
@Composable
fun GlassesGlyph(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val stroke = Stroke(width = w * 0.11f, cap = StrokeCap.Round)
        val r = w * 0.19f
        val y = size.height * 0.6f
        drawCircle(color, r, Offset(w * 0.28f, y), style = stroke)
        drawCircle(color, r, Offset(w * 0.72f, y), style = stroke)
        drawPath(Path().apply { moveTo(w * 0.47f, y - r * 0.15f); quadraticTo(w * 0.5f, y - r * 0.55f, w * 0.53f, y - r * 0.15f) }, color, style = stroke)
        drawLine(color, Offset(w * 0.09f, y - r * 0.2f), Offset(w * 0.15f, y - r * 1.4f), stroke.width, StrokeCap.Round)
        drawLine(color, Offset(w * 0.91f, y - r * 0.2f), Offset(w * 0.85f, y - r * 1.4f), stroke.width, StrokeCap.Round)
    }
}

/**
 * The bubble above the head: the reply typing out, the words you are saying (with how to lock or
 * cancel), or the lock's Send / Cancel. [onOpen] opens the chat; [onClose] puts the reply away (its ✕);
 * [onSendLocked]/[onCancelLocked] end a locked recording. A long reply's "Read it all" shows the rest
 * right there, as tall as [OverlayUiState.bubbleRoom] allows; if even that isn't enough, "Open in chat".
 */
@Composable
fun OverlayBubble(
    ui: OverlayUiState,
    onOpen: () -> Unit,
    onClose: () -> Unit,
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
                Text(ui.micNote.orEmpty(), color = cs.onSurface, style = MaterialTheme.typography.bodyMedium)
            }
            ui.listening -> {
                BubbleBox(shape, cs.primaryContainer) {
                    Text(
                        ui.heard.ifBlank { if (ui.micWaiting) "One moment…" else "Listening…" },
                        color = cs.onPrimaryContainer,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                    // Waiting, the picture on show may still be the last talk's.
                    val shot = ui.shot.takeUnless { ui.micWaiting }
                    val note = when {
                        shot != null && !shot.dropped -> "Your screen goes with this."
                        shot != null -> "Screen dropped: only your words go."
                        else -> null
                    }
                    if (note != null) {
                        Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            Icon(
                                if (shot?.dropped == true) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                contentDescription = null,
                                tint = cs.onPrimaryContainer.copy(alpha = 0.8f),
                                modifier = Modifier.size(14.dp),
                            )
                            Text(note, color = cs.onPrimaryContainer.copy(alpha = 0.8f), style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
                if (ui.locked) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        // Filled, not outlined: an outline vanishes over whatever app is behind it.
                        FilledTonalButton(onClick = onCancelLocked, modifier = Modifier.testTag("overlay_lock_cancel")) { Text("Cancel") }
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
                // While its reveal runs, what the reveal has laid out of it (made safe to draw as it stands); then all of it.
                val running = ui.reveal?.takeIf { !it.finished }
                val source = if (running != null) liveMarkdown(r.text.take(running.laidOut)) else r.text
                val words = rememberReplyText(remember(source) { messagePlainLinked(source) })
                val cut = words.length > BUBBLE_CHARS
                // The ink edge, where the newest words are: on the whole reply, or on the bubble's while it holds them.
                val ink = running?.let { InkBlock(it, it.laidOut) }
                BubbleBox(shape, cs.surfaceContainerHigh, Modifier.clickable(onClick = onOpen).testTag("overlay_reply")) {
                    // As wide as its words, with the ✕ at the far end.
                    Column(Modifier.width(IntrinsicSize.Max)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                r.from,
                                color = cs.onSurfaceVariant,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f),
                            )
                            Box(
                                Modifier.padding(start = 8.dp).offset(x = 6.dp).size(28.dp).clip(CircleShape)
                                    .clickable(onClickLabel = "Close", onClick = onClose).testTag("overlay_reply_close"),
                                contentAlignment = Alignment.Center,
                            ) { Icon(Icons.Filled.Close, contentDescription = "Close", tint = cs.onSurfaceVariant, modifier = Modifier.size(16.dp)) }
                        }
                        if (ui.replyExpanded) {
                            ReplyInFull(words, ink, ui.bubbleRoom, onOpen)
                        } else {
                            var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
                            Text(
                                buildAnnotatedString {
                                    append(words.subSequence(0, minOf(words.length, BUBBLE_CHARS)))
                                    if (cut && !ui.speaking) append("…")
                                },
                                color = cs.onSurface,
                                style = MaterialTheme.typography.bodyMedium,
                                onTextLayout = { layout = it },
                                modifier = Modifier.testTag("overlay_reply_text")
                                    .then(ink?.takeIf { !cut }?.let { Modifier.inkEdge(it) { layout } } ?: Modifier),
                            )
                            if (cut && !ui.speaking) {
                                Text(
                                    "Read it all",
                                    color = cs.primary,
                                    style = MaterialTheme.typography.labelLarge,
                                    modifier = Modifier.padding(top = 4.dp).clip(RoundedCornerShape(6.dp))
                                        .clickable { ui.replyExpanded = true }.testTag("overlay_reply_more"),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * A reply's words with their links, each opening through the platform as the chat's do: a label keeps where it
 * pointed, and a bare URL opens itself (the chat's own linkifier). The bubble, as it is written or read in full,
 * draws a reply only through here; OverlayLinksTest clicks every overlay surface's links.
 */
@Composable
private fun rememberReplyText(reply: PlainMessage): AnnotatedString {
    val platform = LocalPlatform.current
    val color = MaterialTheme.colorScheme.primary
    return remember(reply, platform, color) {
        val styles = TextLinkStyles(SpanStyle(color = color, textDecoration = TextDecoration.Underline))
        val open: (String) -> Unit = { url -> runCatching { platform.openUrl(url) } }
        buildAnnotatedString {
            var at = 0
            for (link in reply.links.sortedBy { it.start }) {
                if (link.start < at || link.end > reply.text.length) continue
                appendLinkified(reply.text.substring(at, link.start), styles, open)
                withLink(LinkAnnotation.Url(link.url, styles) { open(link.url) }) { append(reply.text.substring(link.start, link.end)) }
                at = link.end
            }
            appendLinkified(reply.text.substring(at), styles, open)
        }
    }
}

/** What the bubble keeps for itself around a reply read in full: its padding, the name row, "Open in chat". */
private val REPLY_CHROME = 100.dp

/** A reply read in full stops growing here, however much room there is; past it, it scrolls. */
private val REPLY_MAX = 460.dp

/**
 * A reply read in full: as tall as the [room] the bubble has (up to [REPLY_MAX]), and scrollable past that
 * by finger, trackpad or mouse wheel. While more of it arrives it stays where you are reading, and follows
 * the end only while you are at the end ([ReplyFollow]). Fades at its edges say there is more that way;
 * "Open in chat" carries on in the chat.
 */
@Composable
private fun ReplyInFull(text: AnnotatedString, ink: InkBlock?, room: Dp, onOpen: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val scroll = rememberScrollState()
    LaunchedEffect(scroll) {
        val follow = ReplyFollow()
        snapshotFlow { scroll.value to scroll.maxValue }.collect { (value, max) ->
            follow.onScroll(value, max)?.let { scroll.scrollTo(it) }
        }
    }
    // The bubble's own colour, so the fades read as the text running on under its edge.
    val bubble = cs.surfaceContainerHigh
    Box(Modifier.heightIn(max = (minOf(room, REPLY_MAX) - REPLY_CHROME).coerceAtLeast(80.dp))) {
        var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
        Text(
            text,
            color = cs.onSurface,
            style = MaterialTheme.typography.bodyMedium,
            onTextLayout = { layout = it },
            modifier = Modifier.verticalScroll(scroll).testTag("overlay_reply_full")
                .then(ink?.let { Modifier.inkEdge(it) { layout } } ?: Modifier),
        )
        if (scroll.value > 0) {
            Box(
                Modifier.align(Alignment.TopCenter).fillMaxWidth().height(18.dp)
                    .background(Brush.verticalGradient(listOf(bubble, bubble.copy(alpha = 0f)))),
            )
        }
        if (scroll.value < scroll.maxValue) {
            Box(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(18.dp)
                    .background(Brush.verticalGradient(listOf(bubble.copy(alpha = 0f), bubble))),
            )
        }
    }
    Text(
        "Open in chat",
        color = cs.primary,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(top = 6.dp).clip(RoundedCornerShape(6.dp)).clickable(onClick = onOpen).testTag("overlay_reply_open"),
    )
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

/** The ✕'s circle; [OVERLAY_DROP_BOX] leaves room around it for its growth and shadow. */
private val DROP_CIRCLE = 64.dp

/** The ✕ target's whole size, its circle centred in it: what the host's window is sized and placed by. */
val OVERLAY_DROP_BOX = 96.dp

/** The ✕ a dragged head is dropped on to hide it. */
@Composable
fun OverlayDropTarget(hot: Boolean, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val grow by animateFloatAsState(if (hot) 1.18f else 1f, label = "drop")
    Box(modifier.size(OVERLAY_DROP_BOX), contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(DROP_CIRCLE).scale(grow).shadow(8.dp, CircleShape).clip(CircleShape)
                .background(if (hot) cs.error else cs.inverseSurface.copy(alpha = 0.82f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Close, contentDescription = "Hide", tint = if (hot) cs.onError else cs.inverseOnSurface)
        }
    }
}

/** "Dockie is hidden · Undo", for a few seconds after it was dropped on the ✕. */
@Composable
fun OverlayUndo(name: String, onUndo: () -> Unit, modifier: Modifier = Modifier) {
    Snackbar(
        modifier = modifier.widthIn(max = 360.dp).padding(12.dp).testTag("overlay_undo"),
        action = {
            TextButton(
                onClick = onUndo,
                colors = ButtonDefaults.textButtonColors(contentColor = SnackbarDefaults.actionColor),
                modifier = Modifier.testTag("overlay_undo_button"),
            ) { Text("Undo") }
        },
    ) { Text("$name is hidden", maxLines = 1, overflow = TextOverflow.Ellipsis) }
}

/**
 * The screen taken as you hold it to talk (its glasses on), just below the head: tap it to leave it out of
 * this message, or to put it back.
 */
@Composable
fun OverlayShotThumb(shot: OverlayShot, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(10.dp)
    Column(
        modifier.padding(4.dp).testTag("overlay_shot"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            Modifier.size(width = 52.dp, height = 112.dp).shadow(6.dp, shape).clip(shape)
                .border(2.dp, if (shot.dropped) cs.outline else cs.primary, shape)
                .clickable(onClickLabel = if (shot.dropped) "Send your screen" else "Don't send your screen", onClick = onToggle),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                shot.thumb,
                contentDescription = "Your screen",
                contentScale = ContentScale.Crop,
                alpha = if (shot.dropped) 0.3f else 1f,
                modifier = Modifier.fillMaxSize(),
            )
            if (shot.dropped) Icon(Icons.Filled.Close, contentDescription = null, tint = cs.onSurface)
        }
        Surface(
            shape = RoundedCornerShape(50),
            color = if (shot.dropped) cs.surfaceContainerHigh else cs.primary,
            contentColor = if (shot.dropped) cs.onSurfaceVariant else cs.onPrimary,
            shadowElevation = 2.dp,
        ) {
            Text(
                if (shot.dropped) "Not sent" else "Screen",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
    }
}

/** The quick flash that says the screen was just taken. */
@Composable
fun OverlayFlash(modifier: Modifier = Modifier) {
    val fade = remember { Animatable(0.55f) }
    LaunchedEffect(Unit) { fade.animateTo(0f, tween(240)) }
    Box(modifier.fillMaxSize().background(Color.White.copy(alpha = fade.value)))
}

/**
 * In its chat, what a double-tap on it does: its glasses on (it sees your screen whenever you hold it to
 * talk; Android asks first) or off.
 */
@Composable
fun OverlayGlassesChip(ui: OverlayUiState, name: String, onToggle: () -> Unit) {
    FilterChip(
        selected = ui.screenSharing,
        onClick = onToggle,
        label = { Text(if (ui.screenSharing) "$name sees your screen" else "Let $name see your screen") },
        leadingIcon = { GlassesGlyph(LocalContentColor.current, Modifier.size(AssistChipDefaults.IconSize)) },
        modifier = Modifier.testTag("overlay_glasses"),
    )
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
    /** The host's chips above the composer's field (on Android: looks); null = none. */
    composerChips: (@Composable RowScope.() -> Unit)? = null,
    /** Show another Agent here instead (its id); null hides the switcher. */
    onPickAgent: ((String) -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val sessions by fleet.sessions.collectAsState()
    val agents = remember(sessions) { sessionsByUserOrder(sessions.filter { it.role == "personal_assistant" }) }
    val canSwitch = onPickAgent != null && agents.size > 1
    var switcherOpen by remember { mutableStateOf(false) }
    // Where the header ends, so the switcher drops down right under the name.
    var boxTop by remember { mutableFloatStateOf(0f) }
    var headerBottom by remember { mutableFloatStateOf(0f) }
    val state = rememberChatState(fleet, agent.id)
    val actions = rememberChatActions(fleet, agent.id)
    var draft by remember(agent.id) { mutableStateOf("") }
    LaunchedEffect(agent.id) {
        fleet.markRead(agent.id)
        fleet.markReadOnServer(listOf(agent.id))
    }
    val shape = if (compact) RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp) else RoundedCornerShape(24.dp)
    val headSize = 52.dp
    Box(modifier.testTag("overlay_chat").onGloballyPositioned { boxTop = it.boundsInRoot().top }) {
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
                    Modifier.fillMaxWidth().padding(start = if (compact) headSize + 16.dp else 10.dp, end = 4.dp, top = 6.dp, bottom = 4.dp)
                        .onGloballyPositioned { headerBottom = it.boundsInRoot().bottom },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Its name is the switcher: tap it to show another Agent here (the head's own
                    // gestures are taken: tap chats, hold talks, drag moves).
                    Column(
                        Modifier.weight(1f).clip(RoundedCornerShape(12.dp))
                            .clickable(enabled = canSwitch, onClickLabel = "Switch Agent") { switcherOpen = !switcherOpen }
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                            .testTag("overlay_agent_name"),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                agent.name,
                                style = MaterialTheme.typography.titleMedium,
                                color = cs.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            if (canSwitch) {
                                Icon(
                                    if (switcherOpen) Icons.Filled.ArrowDropUp else Icons.Filled.ArrowDropDown,
                                    contentDescription = null,
                                    tint = cs.onSurfaceVariant,
                                )
                            }
                        }
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
                // The overlay is a composition of its own, outside the shell's providers: the fleet's downloads
                // (#95), so a file the Agent sent keeps downloading when the overlay closes.
                androidx.compose.runtime.CompositionLocalProvider(dev.supermux.ui.work.LocalDownloads provides fleet.downloads) {
                    ChatPanel(
                        session = agent.session,
                        state = state,
                        actions = actions,
                        draft = draft,
                        onDraftChange = { draft = it },
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        showHeader = false,
                        composerChips = composerChips,
                    )
                }
            }
        }
        if (compact) {
            // It rides on the sheet's edge: the same avatar, now awake and attentive.
            Box(Modifier.padding(start = 18.dp).size(headSize)) {
                OverlayHead(agent, rig, ui, size = headSize - 8.dp)
            }
        }
        if (switcherOpen && canSwitch) {
            // A tap anywhere else in the chat closes it.
            Box(
                Modifier.matchParentSize().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                    switcherOpen = false
                },
            )
            OverlayAgentSwitcher(
                fleet = fleet,
                agents = agents,
                currentId = agent.id,
                onPick = { id ->
                    switcherOpen = false
                    if (id != agent.id) onPickAgent?.invoke(id)
                },
                modifier = Modifier.padding(
                    start = if (compact) headSize + 10.dp else 6.dp,
                    top = with(density) { (headerBottom - boxTop).coerceAtLeast(0f).toDp() },
                ),
            )
        }
    }
}

/** The Agents to show in the overlay instead, each with its own avatar; the one shown is ticked. */
@Composable
private fun OverlayAgentSwitcher(
    fleet: FleetStore,
    agents: List<SessionInfo>,
    currentId: String,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val sessions by fleet.sessions.collectAsState()
    val rigs = rememberAgentAvatars(sessions, apiFor = { fleet.appFor(it)?.api })
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = cs.surfaceContainerHigh,
        shadowElevation = 10.dp,
        modifier = modifier.widthIn(min = 220.dp, max = 300.dp).testTag("overlay_agent_switcher"),
    ) {
        Column(Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState()).padding(vertical = 6.dp)) {
            Text(
                "Show in the overlay",
                style = MaterialTheme.typography.labelMedium,
                color = cs.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
            for (a in agents) {
                val on = a.id == currentId
                val asleep = a.status != null && a.status != "active"
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .background(if (on) cs.secondaryContainer.copy(alpha = 0.55f) else androidx.compose.ui.graphics.Color.Transparent)
                        .clickable { onPick(a.id) }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                        .testTag("overlay_agent_${a.id}"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    val rig = rigs[a.id]
                    if (rig != null) {
                        AvatarRigView(rig, state = if (asleep) "asleep" else "idle", seed = a.id, size = 32)
                    } else {
                        AgentAvatar(a.name, online = !asleep, working = false, size = 32)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(a.name, style = MaterialTheme.typography.bodyLarge, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (a.isDefault == true) {
                            Text("Default", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                        }
                    }
                    if (on) Icon(Icons.Filled.Check, contentDescription = "Shown now", tint = cs.primary)
                }
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
