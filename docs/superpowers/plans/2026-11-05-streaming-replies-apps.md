# Streaming replies — Plan B: the apps (card #76)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The shared Kotlin app shows an agent's reply and thinking while they are written, from Plan A's `reply_stream` frames. The stored message (or reasoning row) takes the live row's place without a jump. It is on for the desktop first.

**Architecture:**
- `HostState` gets a `live` slice: session → its live replies, at most one per kind.
- The reducer applies `reply_stream` frames by offset. It drops a live reply on `reply_stream_end`, or when a stored row with the same `stream` arrives; that happens in the same state update that appends the stored row.
- `ChatPanel` draws live rows after the timeline, keyed like the stored rows they become, and the #1 autoscroll measures them.
- `subscribe.streams: 1` is sent only from surfaces in `LIVE_REPLY_SURFACES`, which starts as desktop only.

**Tech Stack:** Kotlin Multiplatform (`apps/shared` commonMain/commonTest, `apps/ui` commonMain/jvmTest), Compose Multiplatform, kotlinx.serialization.

Spec: `docs/superpowers/specs/2026-11-05-streaming-replies-design.md`. Plan A (broker) is done: `e09a1ccdf..ba49a588e`.

**Gradle on this box** (from `apps/`, always under the heavy-job lock):

```bash
G() { flock -w 3600 /tmp/supermux-heavy.lock systemd-run --user --scope -q -p MemoryMax=8G -- ./gradlew -Pkotlin.compiler.execution.strategy=in-process --no-daemon -Dorg.gradle.jvmargs=-Xmx2g "$@"; }
```

Rounds take ~12 min when the box is loaded. Batch the tests: one shared round, one ui round.

---

### Task 1: Protocol types (shared)

**Files:** Modify `apps/shared/src/commonMain/kotlin/dev/supermux/proto/Frames.kt`; Test `apps/shared/src/commonTest/kotlin/dev/supermux/proto/LiveFramesTest.kt` (new)

- [ ] **Step 1: Failing test** `LiveFramesTest.kt`

```kotlin
package dev.supermux.proto

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/** Card #76: the live-text frames as the broker writes them, decoded with BrokerClient's settings. */
class LiveFramesTest {
    private val json = Json { ignoreUnknownKeys = true; classDiscriminator = "type" }

    @Test fun replyStreamDecodes() {
        val f = json.decodeFromString<ServerFrame>("""{"type":"reply_stream","session":"s1","stream":"k1","at":3,"text":"lo"}""")
        assertEquals(ServerFrame.ReplyStream(session = "s1", stream = "k1", at = 3, text = "lo"), f)
        assertEquals("reply", (f as ServerFrame.ReplyStream).kind)
    }

    @Test fun thinkingStreamDecodes() {
        val f = json.decodeFromString<ServerFrame>("""{"type":"reply_stream","session":"s1","stream":"t1","kind":"thinking","at":0,"text":"Plan"}""")
        assertEquals("thinking", (f as ServerFrame.ReplyStream).kind)
    }

    @Test fun endDecodes() {
        assertEquals(
            ServerFrame.ReplyStreamEnd(session = "s1", stream = "k1"),
            json.decodeFromString<ServerFrame>("""{"type":"reply_stream_end","session":"s1","stream":"k1"}"""),
        )
    }

    @Test fun storedRowsCarryTheirStream() {
        val m = json.decodeFromString<ServerFrame>("""{"type":"message_append","session":"s1","entry":{"id":"out:web:1","ts":"t","direction":"outbound","text":"Hi","stream":"k1"}}""")
        assertEquals("k1", (m as ServerFrame.MessageAppend).entry.stream)
        val a = json.decodeFromString<ServerFrame>("""{"type":"activity_append","session":"s1","event":{"ts":"t","kind":"reasoning","title":"Thinking","detail":"Plan","stream":"t1"}}""")
        assertEquals("t1", (a as ServerFrame.ActivityAppend).event.stream)
    }

    @Test fun snapshotCarriesOpenStreams() {
        val s = json.decodeFromString<ServerFrame>("""{"type":"snapshot","streams":{"s1":[{"stream":"k1","kind":"reply","text":"so far"}]}}""")
        assertEquals(mapOf("s1" to listOf(LiveStreamDto("k1", "reply", "so far"))), (s as ServerFrame.Snapshot).streams)
    }
}
```

- [ ] **Step 2: Implement** in `Frames.kt`:
  - `LogEntry`, after `val error: Boolean = false,`:

```kotlin
    /** Card #76: the live stream this stored reply replaces (only on the live `message_append`; never stored). */
    val stream: String? = null,
```

  - `ActivityEvent`, after `val sender: String? = null,`:

```kotlin
    /** Card #76: on a reasoning row, the live thinking stream it replaces. */
    val stream: String? = null,
```

  - before `sealed interface ServerFrame`:

```kotlin
/** Card #76: an open live stream handed over in the snapshot (`streams`), so a reconnect shows the text so far. */
@Serializable
data class LiveStreamDto(val stream: String, val kind: String = "reply", val text: String = "")
```

  - `ServerFrame.Snapshot`, after `val archivedLazy: Boolean = false,`:

```kotlin
        /** Card #76: session → its open live streams (only when `subscribe` asked for `streams`). */
        val streams: Map<String, List<LiveStreamDto>> = emptyMap(),
```

  - after `MessageAppend`:

```kotlin
    /**
     * Card #76: more of a reply (or, `kind` "thinking", of the agent's thinking) while it is written. [at] is the
     * offset of [text] in the stream; `at` 0 is the whole text so far.
     */
    @Serializable @SerialName("reply_stream")
    data class ReplyStream(val session: String, val stream: String, val kind: String = "reply", val at: Int = 0, val text: String = "") : ServerFrame

    /** Card #76: a live stream ended with nothing stored for it: drop it. */
    @Serializable @SerialName("reply_stream_end")
    data class ReplyStreamEnd(val session: String, val stream: String) : ServerFrame
```

- [ ] **Step 3:** Covered by the shared round in Task 3.

---

### Task 2: Live state and reducer (shared)

**Files:**
- Create `apps/shared/src/commonMain/kotlin/dev/supermux/state/LiveReplies.kt`
- Modify `HostState.kt`, `HostReducer.kt`
- Test `apps/shared/src/commonTest/kotlin/dev/supermux/state/HostReducerLiveTest.kt` (new)

- [ ] **Step 1: Failing test** `HostReducerLiveTest.kt`

```kotlin
package dev.supermux.state

import dev.supermux.proto.ActivityEvent
import dev.supermux.proto.LiveStreamDto
import dev.supermux.proto.LogEntry
import dev.supermux.proto.ServerFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/** Card #76: live replies in the host state. */
class HostReducerLiveTest {
    private fun delta(at: Int, text: String, stream: String = "k1", kind: String = "reply", session: String = "s1") =
        ServerFrame.ReplyStream(session = session, stream = stream, kind = kind, at = at, text = text)

    private fun HostState.text(session: String = "s1", stream: String = "k1") = live[session]?.firstOrNull { it.stream == stream }?.text

    @Test fun deltasAppendByOffset() {
        var s = reduceHostFrame(HostState(), delta(0, "Hel"))
        s = reduceHostFrame(s, delta(3, "lo"))
        assertEquals("Hello", s.text())
        // A frame sent twice (at < length) adds only what is new.
        s = reduceHostFrame(s, delta(3, "lo there"))
        assertEquals("Hello there", s.text())
        // at 0 is the whole text so far.
        s = reduceHostFrame(s, delta(0, "Hello there!"))
        assertEquals("Hello there!", s.text())
    }

    @Test fun aGapWaitsForTheWholeText() {
        val s = reduceHostFrame(HostState(), delta(0, "ab"))
        assertSame(s, reduceHostFrame(s, delta(5, "zz")))
        assertSame(HostState().live, reduceHostFrame(HostState(), delta(4, "late")).live)
    }

    @Test fun thinkingAndReplyLiveSideBySideThinkingFirst() {
        var s = reduceHostFrame(HostState(), delta(0, "answer", stream = "k1"))
        s = reduceHostFrame(s, delta(0, "plan", stream = "t1", kind = "thinking"))
        assertEquals(listOf("t1", "k1"), s.live["s1"]!!.map { it.stream })
        // A new stream of a kind replaces the last of that kind.
        s = reduceHostFrame(s, delta(0, "next", stream = "k2"))
        assertEquals(listOf("t1", "k2"), s.live["s1"]!!.map { it.stream })
    }

    @Test fun theStoredMessageReplacesItsLiveReplyInOneStep() {
        val s = reduceHostFrame(HostState(), delta(0, "Hello"))
        val entry = LogEntry(id = "out:web:1", ts = "t", direction = "outbound", text = "Hello", stream = "k1")
        val next = reduceHostFrame(s, ServerFrame.MessageAppend("s1", entry))
        assertEquals(null, next.live["s1"])
        assertEquals(listOf(entry), next.messages["s1"])
    }

    @Test fun theStoredThinkingReplacesTheLiveThinking() {
        val s = reduceHostFrame(HostState(), delta(0, "Plan", stream = "t1", kind = "thinking"))
        val row = ActivityEvent(ts = "t", kind = "reasoning", title = "Thinking", detail = "Plan", stream = "t1")
        assertEquals(null, reduceHostFrame(s, ServerFrame.ActivityAppend("s1", row)).live["s1"])
    }

    @Test fun anEndDropsItAndOtherSessionsKeepTheirs() {
        var s = reduceHostFrame(HostState(), delta(0, "a"))
        s = reduceHostFrame(s, delta(0, "b", session = "s2", stream = "k9"))
        s = reduceHostFrame(s, ServerFrame.ReplyStreamEnd("s1", "k1"))
        assertEquals(null, s.live["s1"])
        assertEquals("b", s.text("s2", "k9"))
    }

    @Test fun theSnapshotIsTheTruth() {
        val s = reduceHostFrame(HostState(), delta(0, "stale"))
        val snap = reduceHostFrame(s, ServerFrame.Snapshot(streams = mapOf("s2" to listOf(LiveStreamDto("k5", "reply", "so far")))))
        assertEquals(null, snap.live["s1"])
        assertEquals("so far", snap.text("s2", "k5"))
    }
}
```

- [ ] **Step 2: Implement** `LiveReplies.kt`

```kotlin
package dev.supermux.state

import dev.supermux.proto.LiveStreamDto
import dev.supermux.proto.ServerFrame

/**
 * Card #76: a reply (or the agent's thinking) while the agent writes it. Never stored: the stored message
 * (or reasoning row) with the same [stream] takes its place. At most one per kind per session.
 */
data class LiveReply(val stream: String, val kind: String, val text: String) {
    val thinking: Boolean get() = kind == KIND_THINKING

    companion object {
        const val KIND_THINKING = "thinking"
    }
}

/** Thinking first: it comes before the reply it leads to. */
private fun List<LiveReply>.ordered(): List<LiveReply> = sortedBy { if (it.thinking) 0 else 1 }

/**
 * One `reply_stream` frame: `at` 0 is the whole text so far, `at` == the text's length appends, a frame sent
 * twice adds only what is new, and a gap (or a stream first seen past 0) waits for the next `at` 0.
 */
internal fun applyLiveDelta(live: Map<String, List<LiveReply>>, frame: ServerFrame.ReplyStream): Map<String, List<LiveReply>> {
    val list = live[frame.session].orEmpty()
    val current = list.firstOrNull { it.stream == frame.stream }
    val have = current?.text
    val text = when {
        frame.at == 0 -> frame.text
        have == null || frame.at > have.length -> return live
        frame.at == have.length -> have + frame.text
        else -> {
            val known = have.length - frame.at
            if (frame.text.length <= known) return live
            have + frame.text.substring(known)
        }
    }
    val others = list.filter { it.stream != frame.stream && it.kind != frame.kind }
    return live + (frame.session to (others + LiveReply(frame.stream, frame.kind, text)).ordered())
}

/** The live reply [stream] of [session] is over (its stored row arrived, or nothing will be stored). */
internal fun dropLive(live: Map<String, List<LiveReply>>, session: String, stream: String?): Map<String, List<LiveReply>> {
    if (stream == null) return live
    val list = live[session] ?: return live
    val next = list.filterNot { it.stream == stream }
    return when {
        next.size == list.size -> live
        next.isEmpty() -> live - session
        else -> live + (session to next)
    }
}

/** The snapshot's open streams: a reconnect shows the text so far, and nothing older survives it. */
internal fun liveFromSnapshot(streams: Map<String, List<LiveStreamDto>>): Map<String, List<LiveReply>> =
    streams.mapValues { (_, l) -> l.map { LiveReply(it.stream, it.kind, it.text) }.ordered() }.filterValues { it.isNotEmpty() }
```

- [ ] **Step 3:** In `HostState`, after `val removedWorktreeIds: Set<String> = emptySet(),`:

```kotlin
    /** Card #76: session → the reply / thinking being written now (see [LiveReply]). */
    val live: Map<String, List<LiveReply>> = emptyMap(),
```

- [ ] **Step 4:** In `HostReducer.kt`:
  - in the `Snapshot` branch's `state.copy(`, add `live = liveFromSnapshot(frame.streams),`;
  - in `MessageAppend`'s `state.copy(`, add `live = dropLive(state.live, frame.session, frame.entry.stream),`;
  - replace the `ActivityAppend` branch with:

```kotlin
    is ServerFrame.ActivityAppend -> state.copy(
        activity = state.activity + (frame.session to ((state.activity[frame.session] ?: emptyList()) + frame.event)),
        live = dropLive(state.live, frame.session, frame.event.stream),
    )
    is ServerFrame.ReplyStream -> {
        val next = applyLiveDelta(state.live, frame)
        if (next === state.live) state else state.copy(live = next)
    }
    is ServerFrame.ReplyStreamEnd -> {
        val next = dropLive(state.live, frame.session, frame.stream)
        if (next === state.live) state else state.copy(live = next)
    }
```

---

### Task 3: Opt in from the desktop; a flow for the panel (shared)

**Files:**
- Modify `HostStore.kt` (`subscribeFrameJson`, the `client = BrokerClient(…)` line, projections)
- Modify `net/BrokerClient.kt` (no `println` per live frame)
- Test: add to `apps/shared/src/jvmTest/kotlin/dev/supermux/state/HostStoreLogTailTest.kt`

- [ ] **Step 1: Failing test** (next to `subscribeAsksForTailsExceptTheChatsOnScreen`)

```kotlin
    @Test fun subscribeAsksForLiveTextOnlyWhenTheSurfaceDoes() {
        val off = Json.parseToJsonElement(subscribeFrameJson(listOf("a"))).jsonObject
        assertEquals(null, off["streams"])
        val on = Json.parseToJsonElement(subscribeFrameJson(listOf("a"), streams = true)).jsonObject
        assertEquals(1, on["streams"]!!.jsonPrimitive.int)
        assertEquals(setOf("desktop"), LIVE_REPLY_SURFACES)
    }
```

- [ ] **Step 2: Implement**
  - `subscribeFrameJson(fullLogs: Collection<String>, trace: String? = null, req: String? = null, streams: Boolean = false)`, and at the end of its builder:

```kotlin
    // Card #76: replies while they are written (reply_stream frames), for the chats on screen.
    if (streams) put("streams", 1)
```

  - above it:

```kotlin
/**
 * Card #76: the surfaces that ask for live replies. Desktop first; each platform joins once it has been checked
 * on real hardware (Android, iOS, web).
 */
internal val LIVE_REPLY_SURFACES = setOf("desktop")
```

  - in `HostStore`, change the `client` line to:

```kotlin
        subscribeFrame = { req -> subscribeFrameJson(viewingSessionIds, trace = PerfTrace.launchId, req = req, streams = deps.clientInfo?.surface in LIVE_REPLY_SURFACES) },
```

  - and next to the `councils` projection:

```kotlin
    /** Card #76: session → its live reply / thinking (only the chats on screen get any). */
    val liveReplies: StateFlow<Map<String, List<LiveReply>>> =
        _state.map { it.live }.stateIn(projectionScope, SharingStarted.Eagerly, emptyMap())
```

  - `BrokerClient.kt`: change `println("[BrokerClient] rx ${parsed::class.simpleName}")` to

```kotlin
                                    // Card #76: up to 20 live frames a second per chat — not worth a log line each.
                                    if (parsed !is ServerFrame.ReplyStream) println("[BrokerClient] rx ${parsed::class.simpleName}")
```

- [ ] **Step 3: Run the shared round**

```bash
cd apps && G :shared:jvmTest --tests 'dev.supermux.proto.LiveFramesTest' --tests 'dev.supermux.state.HostReducerLiveTest' --tests 'dev.supermux.state.HostStoreLogTailTest' --tests 'dev.supermux.proto.*' --tests 'dev.supermux.state.HostReducer*'
```

Expected: all pass.

- [ ] **Step 4: Commit**

```bash
git add apps/shared
git commit -m "feat(apps): live replies in the host state; the desktop asks for them (#76)"
```

---

### Task 4: Markdown that is still being written (shared)

**Files:** Create `apps/shared/src/commonMain/kotlin/dev/supermux/chat/LiveMarkdown.kt`; Test `apps/shared/src/commonTest/kotlin/dev/supermux/chat/LiveMarkdownTest.kt`

- [ ] **Step 1: Failing test**

```kotlin
package dev.supermux.chat

import kotlin.test.Test
import kotlin.test.assertEquals

class LiveMarkdownTest {
    @Test fun anUnfinishedTableRowWaitsForItsLine() =
        assertEquals("| a | b |\n|---|---|\n", liveMarkdown("| a | b |\n|---|---|\n| Par"))

    @Test fun openBoldAndCodeAreClosed() {
        assertEquals("this is **bo**", liveMarkdown("this is **bo"))
        assertEquals("run `ls -l`", liveMarkdown("run `ls -l"))
        assertEquals("done **bold** and `code`", liveMarkdown("done **bold** and `code`"))
    }

    @Test fun aBareOpenerIsHeldBack() {
        assertEquals("this is ", liveMarkdown("this is **"))
        assertEquals("run ", liveMarkdown("run `"))
    }

    @Test fun insideAnOpenFenceNothingChanges() =
        assertEquals("```py\nx = \"**\"", liveMarkdown("```py\nx = \"**\""))

    @Test fun onlyTheLastParagraphCounts() =
        assertEquals("a ** b\n\nnow **x**", liveMarkdown("a ** b\n\nnow **x"))
}
```

- [ ] **Step 2: Implement**

```kotlin
package dev.supermux.chat

/**
 * Card #76: Markdown that is still being written, made safe to draw as it is.
 * - An unfinished table row (the last line, starting with `|`) waits for its newline, so a table does not
 *   flicker between text and table.
 * - In the last paragraph, an open `` ` `` or `**` is closed, so code and emphasis show as such instead of
 *   their markers; a bare opener at the very end is held back.
 * - An open code fence needs nothing: CommonMark runs it to the end of the text.
 */
fun liveMarkdown(text: String): String {
    var t = text
    val lastBreak = t.lastIndexOf('\n')
    if (t.substring(lastBreak + 1).trimStart().startsWith("|")) t = t.substring(0, lastBreak + 1)
    if (insideFence(t)) return t
    val para = t.substring(t.lastIndexOf("\n\n").let { if (it < 0) 0 else it + 2 })
    if (para.count { it == '`' } % 2 == 1) {
        return if (t.endsWith("`")) t.dropLast(1) else "$t`"
    }
    val outsideCode = para.replace(Regex("`[^`]*`"), "")
    if (Regex("""\*\*""").findAll(outsideCode).count() % 2 == 1) {
        return if (t.endsWith("**")) t.dropLast(2) else "$t**"
    }
    return t
}

private fun insideFence(t: String): Boolean =
    t.lineSequence().count { line -> line.trimStart().let { it.startsWith("```") || it.startsWith("~~~") } } % 2 == 1
```

- [ ] **Step 3:** Run `cd apps && G :shared:jvmTest --tests 'dev.supermux.chat.LiveMarkdownTest'`, expect PASS. Batch it with Task 3's round if both are ready.

- [ ] **Step 4: Commit** `git add apps/shared && git commit -m "feat(apps): draw Markdown that is still being written without flicker (#76)"`

---

### Task 5: Live rows in the chat (ui)

**Files:**
- Create `apps/ui/src/commonMain/kotlin/dev/supermux/ui/chat/LiveRows.kt`
- Modify `ChatPanel.kt` (`ChatState`, `rememberChatState`, `timelineItemKey`, the `timelineItems` memo, autoscroll, `LazyColumn`)
- Test: add to `apps/ui/src/jvmTest/kotlin/dev/supermux/ui/chat/ChatAutoscrollTest.kt`

- [ ] **Step 1: Failing tests** (in `ChatAutoscrollTest`)

```kotlin
    private fun live(text: String) = dev.supermux.state.LiveReply(stream = "k1", kind = "reply", text = text)

    @Test fun at_the_end_a_live_reply_is_followed_as_it_grows() = runComposeUiTest {
        panel()
        state = state.copy(live = listOf(live("Live paragraph one.")))
        waitForIdle()
        assertTrue(onScreen("Live paragraph one."), "the live reply is followed into view")
        state = state.copy(live = listOf(live("Live paragraph one.\n\n" + paragraphs("Live", 12) + "\n\nLive tail.")))
        waitForIdle()
        assertTrue(onScreen("Live tail."), "its growing end stays in view")
    }

    @Test fun a_live_reply_growing_does_not_move_a_reader_inside_the_last_reply() = runComposeUiTest {
        panel()
        val anchor = readInsideTheLastReply()
        val before = topOf(anchor)!!
        state = state.copy(live = listOf(live("Live paragraph one.")))
        waitForIdle()
        state = state.copy(live = listOf(live("Live paragraph one.\n\nLive paragraph two.")))
        waitForIdle()
        assertEquals(before, topOf(anchor) ?: Float.NaN, 1f, "live text below the reader moved the transcript")
    }

    @Test fun the_stored_message_takes_the_live_replys_place_without_a_jump() = runComposeUiTest {
        panel()
        state = state.copy(live = listOf(live("Live paragraph one.\n\nThe live end.")))
        waitForIdle()
        val before = topOf("Live paragraph one.")!!
        state = state.copy(
            messages = state.messages + entry(7, "Live paragraph one.\n\nThe live end.").copy(stream = "k1"),
            live = emptyList(),
        )
        waitForIdle()
        assertEquals(before, topOf("Live paragraph one.") ?: Float.NaN, 1f, "the swap moved the reply")
    }
```

- [ ] **Step 2: Implement** `LiveRows.kt`

```kotlin
package dev.supermux.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.supermux.chat.liveMarkdown
import dev.supermux.state.LiveReply
import dev.supermux.ui.FilePathRef
import dev.supermux.ui.theme.Space

/**
 * Card #76: the reply while the agent writes it. Same look and padding as the stored reply that takes its place
 * (TimelineItemRow's message row), minus the time / copy row that only a finished message has.
 */
@Composable
internal fun LiveReplyRow(reply: LiveReply, onOpenFile: (FilePathRef) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = Space.sm).testTag("chat-live-reply")) {
        LinkSelectionContainer {
            MarkdownBody(text = liveMarkdown(reply.text), modifier = Modifier.fillMaxWidth(), onOpenFile = onOpenFile, linkify = true)
        }
    }
}

/**
 * Card #76: the agent's thinking while it writes it: its newest lines, muted, under "Thinking…". Once the reply
 * starts it folds to the label, like the stored thinking row that takes its place.
 */
@Composable
internal fun LiveThinkingRow(thinking: LiveReply, folded: Boolean) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().testTag("activity-reasoning-live").padding(vertical = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            if (!folded) CircularProgressIndicator(Modifier.size(12.dp), color = cs.onSurfaceVariant, strokeWidth = 1.5.dp)
            Text("Thinking…", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
        }
        if (!folded) {
            Text(
                thinkingTail(thinking.text),
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
                modifier = Modifier.padding(top = Space.xs),
            )
        }
    }
}

/** The newest few lines of a long think: the row stays small while the text keeps coming. */
internal fun thinkingTail(text: String, maxLines: Int = 6, maxChars: Int = 600): String {
    val lines = text.trimEnd().lines()
    val tail = lines.takeLast(maxLines).joinToString("\n")
    return if (tail.length <= maxChars) tail else "…" + tail.takeLast(maxChars)
}
```

Match `FilePathRef` and `Space` to the imports `Timeline.kt` uses; copy its import lines for those two names.

- [ ] **Step 3: Wire `ChatPanel.kt`**
  - `ChatState`, after `val historyLoaded: Boolean = true,`:

```kotlin
    /** Card #76: this chat's reply / thinking being written now (thinking first). */
    val live: List<dev.supermux.state.LiveReply> = emptyList(),
```

  - `rememberChatState`: add `val liveMap by app.liveReplies.collectAsState()`, and `live = liveMap[sessionId].orEmpty(),` in the returned `ChatState`.
  - `timelineItemKey`:

```kotlin
    // Card #76: a stored row with a stream keeps the key its live row had, so the swap keeps its slot.
    is TimelineItem.Msg -> "m:${item.entry.stream ?: item.entry.id}"
    is TimelineItem.Tool -> "t:${item.event.callId ?: "${item.event.kind}:${item.event.seq}:${item.event.ts}"}"
    is TimelineItem.Activity -> item.event.stream?.let { "a:$it" } ?: "a:${item.event.kind}:${item.event.seq}:${item.event.ts}"
```

  - the `timelineItems` memo. While anything is live, the redacted "Thinking…" spinner row is not needed: the live rows are the progress.

```kotlin
    val live = state.live
    val liveIdle = live.isEmpty()
    val timelineItems = remember(messages, activity, hideTools, subagents, working, councils, liveIdle) {
        mergeTimeline(messages, activity, hideTools = hideTools, subagents = subagents, working = working && liveIdle, councils = councils)
    }
```

  - autoscroll. Count live rows as the end of the transcript:
    - `lastShown` becomes `remember(timelineItems, toolRuns, live.size) { if (live.isNotEmpty()) timelineItems.size + live.size - 1 else shownTail(timelineItems.size, toolRuns).first }`;
    - in the follow-new-content `LaunchedEffect`, replace every `timelineItems.size` with `rowCount`, defined just before it as `val rowCount = timelineItems.size + live.size`; the effect's key `timelineItems.size` becomes `rowCount`.
  - `LazyColumn`, right after the `itemsIndexed(timelineItems, …) { … }` block:

```kotlin
                // Card #76: what the agent is writing right now, keyed like the stored rows that replace it.
                items(live, key = { if (it.thinking) "a:${it.stream}" else "m:${it.stream}" }) { row ->
                    if (row.thinking) LiveThinkingRow(row, folded = live.any { !it.thinking })
                    else LiveReplyRow(row, onOpenFile)
                }
```

- [ ] **As built, two changes from the steps above:**
  - Live rows are drawn from `liveRows`: `live` minus any row whose key (`liveRowKey`, the same one its stored
    row gets) is already in `timelineKeys`. Two `LazyColumn` rows with one key throw and freeze the whole UI.
    The reducer already drops a live row in the step that appends its stored row; this is the belt to that
    brace. `lastShown`, `rowCount` and the `items(…)` call use `liveRows`.
  - The swap test allows the stored message's time / copy row: at the end of the transcript that growth is
    followed (the #1 rule), so the reply may move up by that row's height (≤ 64 px), and its end stays in
    view.

- [ ] **Step 4: Run the ui round**

```bash
cd apps && G :ui:jvmTest --tests 'dev.supermux.ui.chat.ChatAutoscrollTest' --tests 'dev.supermux.ui.chat.ChatPanel*'
```

Expected: the 5 existing and 3 new autoscroll tests pass, and the ChatPanel tests still pass.

- [ ] **Step 5: Commit** `git add apps/ui && git commit -m "feat(apps): the chat shows the reply and thinking while they are written (#76)"`

---

### Task 6: Desktop verification against the shadow broker

- [ ] Build and run the desktop app on this box, pointed at the shadow broker (`~/.mux/previews/stream76/start.sh`, port 9976). Use a minted device: Add host with `http://127.0.0.1:9976` and its token. Hot reload on the Mac only with `~/work/supermux-hotreload/.lease`.
- [ ] With a Claude (Haiku) and a Grok session, record a screen capture of one turn. Check:
  - the thinking shows live and folds when the reply starts;
  - the reply grows word by word;
  - the stored message replaces it with no jump;
  - scrolling up mid-reply stays put;
  - an old desktop build (`dev` without this branch) shows only the final message.
- [ ] `PerfTrace.countRecompose("chat")` over one streamed turn: note the count in the card comment.
