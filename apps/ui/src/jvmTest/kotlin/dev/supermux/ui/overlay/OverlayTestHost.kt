package dev.supermux.ui.overlay

import dev.supermux.net.BrokerApi
import dev.supermux.proto.ClientFrame
import dev.supermux.proto.LogEntry
import dev.supermux.proto.ServerFrame
import dev.supermux.proto.SessionInfo
import dev.supermux.state.HostStore
import dev.supermux.ui.shell.shellTestDeps
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import java.util.Collections

/** The Agent the overlay tests float: one personal assistant on one host. */
internal val overlaySession = SessionInfo(id = "pa1", name = "Anya", workdir = "/w", agent = "claude", role = "personal_assistant")

internal val overlayAgent = OverlayAgent(
    id = overlaySession.id, name = overlaySession.name, online = true, connected = true, working = false, unread = false,
    lastTs = null, session = overlaySession,
)

/** A host holding [overlaySession], never connected; every frame the app sends lands in [sent]. */
internal fun overlayStore(sent: MutableList<ClientFrame> = Collections.synchronizedList(mutableListOf())): HostStore = HostStore(
    baseUrl = "ws://test:9898",
    token = "t",
    scope = CoroutineScope(Dispatchers.Default),
    deps = shellTestDeps(),
    connectOnInit = false,
    sendFrameOverride = { sent.add(it) },
    apiOverride = BrokerApi("ws://test:9898", "t", HttpClient(MockEngine { respond("{}", HttpStatusCode.OK) })),
).also { it.reduce(ServerFrame.Snapshot(sessions = listOf(overlaySession), accepts = listOf("live"))) }

/** The Agent writes more of a reply ([at] 0 is the whole text so far), as `reply_stream` frames bring it. */
internal fun HostStore.agentWrites(text: String, at: Int = 0, stream: String = "k1") =
    reduce(ServerFrame.ReplyStream(session = overlaySession.id, stream = stream, kind = "reply", at = at, text = text))

/** The Agent's reply is stored: the one [stream] was writing, or (null) one nobody saw being written. */
internal fun HostStore.agentReplies(text: String, stream: String? = null, id: String = "m1") = reduce(
    ServerFrame.MessageAppend(
        session = overlaySession.id,
        entry = LogEntry(id = id, ts = "2026-11-06T20:00:00Z", direction = "outbound", op = "reply", text = text, stream = stream),
    ),
)
