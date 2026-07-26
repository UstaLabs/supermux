# Android Session Drag and Swipe Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make Android session rows reorder reliably in flat and grouped views and replace immediate swipe execution with reveal-then-tap action trays.

**Architecture:** Introduce a pure interaction model for view-aware reorder scopes, working orders, and swipe action mapping. Render every reorderable session as a top-level lazy-list item, update a screen-local working order synchronously during drag, and persist only on drag stop. Flat mode scopes by status; grouped mode scopes by project plus status. Replace `SwipeToDismissBox` with a direction-locking horizontal reveal whose anchors have no side effects.

**Tech Stack:** Kotlin 2.3.21, Jetpack Compose, Material 3, Calvin Reorderable 3.1.0, Kotlin Test, Android Compose UI Test, Gradle, ADB

---

## File Structure

- Create `apps/android/src/main/kotlin/dev/supermux/android/session/SessionListInteractions.kt`: pure reorder scopes, working-order functions, and swipe-action mapping.
- Create `apps/android/src/test/kotlin/dev/supermux/android/session/SessionListInteractionsTest.kt`: unit specification for move validation, working-order projection, and action mapping.
- Create `apps/android/src/main/kotlin/dev/supermux/android/session/SwipeActionRow.kt`: reusable reveal-then-tap row shell with no action-on-swipe behavior.
- Create `apps/android/src/androidTest/kotlin/dev/supermux/android/session/SwipeActionRowTest.kt`: touch-input tests for reveal, explicit tap, and single-open-row behavior.
- Modify `apps/android/src/main/kotlin/dev/supermux/android/session/SessionListScreen.kt`: replace dismiss swipe, own drag working state, flatten grouped rows, and persist once on drop.
- Modify `apps/android/build.gradle.kts`: add Compose instrumentation-test dependencies.

### Task 1: Specify and implement the pure interaction model

**Files:**
- Create: `apps/android/src/test/kotlin/dev/supermux/android/session/SessionListInteractionsTest.kt`
- Create: `apps/android/src/main/kotlin/dev/supermux/android/session/SessionListInteractions.kt`

- [ ] **Step 1: Write the failing unit tests**

Create `SessionListInteractionsTest.kt` with:

```kotlin
package dev.supermux.android.session

import dev.supermux.proto.SessionInfo
import dev.supermux.session.SectionKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SessionListInteractionsTest {
    private fun row(
        id: String,
        project: String,
        section: SectionKey = SectionKey.IN_PROGRESS,
        muted: Boolean = false,
    ) = SessionInfo(
        id = id,
        name = id,
        workdir = project,
        repo_root = project,
        agent = "claude",
        userStatus = section.wire,
        mute = muted,
    )

    @Test
    fun moveWithinScope_returnsImmediateWorkingOrder() {
        val rows = listOf(row("a", "/p"), row("b", "/p"), row("c", "/p"))
        assertEquals(
            listOf("b", "c", "a"),
            moveWithinScope(rows, emptyMap(), "a", "c")?.orderedIds,
        )
    }

    @Test
    fun moveWithinScope_rejectsDifferentProjectsAndSections() {
        val rows = listOf(
            row("a", "/one"),
            row("b", "/two"),
            row("c", "/one", SectionKey.DRAFT),
        )
        assertNull(moveWithinScope(rows, emptyMap(), "a", "b"))
        assertNull(moveWithinScope(rows, emptyMap(), "a", "c"))
    }

    @Test
    fun applyWorkingOrders_reordersOnlyMatchingScopeSlots() {
        val rows = listOf(
            row("a", "/one"),
            row("x", "/two"),
            row("b", "/one"),
            row("y", "/two"),
        )
        val scope = reorderScope(rows.first())
        assertEquals(
            listOf("b", "x", "a", "y"),
            applyWorkingOrders(rows, mapOf(scope to listOf("b", "a"))).map { it.id },
        )
    }

    @Test
    fun swipeActions_matchEachSessionSection() {
        assertEquals(
            SessionSwipeActions(SessionSwipeAction.Mute, SessionSwipeAction.Settle),
            sessionSwipeActions(row("a", "/p")),
        )
        assertEquals(
            SessionSwipeActions(SessionSwipeAction.Unmute, SessionSwipeAction.Settle),
            sessionSwipeActions(row("a", "/p", muted = true)),
        )
        assertEquals(
            SessionSwipeActions(SessionSwipeAction.Edit, SessionSwipeAction.Discard),
            sessionSwipeActions(row("d", "/p", SectionKey.DRAFT)),
        )
        assertEquals(
            SessionSwipeActions(SessionSwipeAction.Activate, null),
            sessionSwipeActions(row("s", "/p", SectionKey.SETTLED)),
        )
    }
}
```

- [ ] **Step 2: Run the focused unit test and verify RED**

Run:

```bash
cd apps && ./gradlew :android:testDebugUnitTest --tests dev.supermux.android.session.SessionListInteractionsTest
```

Expected: compilation fails because `moveWithinScope`, `applyWorkingOrders`, and the swipe-action types do not exist.

- [ ] **Step 3: Implement the pure model**

Create `SessionListInteractions.kt` with:

```kotlin
package dev.supermux.android.session

import dev.supermux.proto.SessionInfo
import dev.supermux.session.SectionKey
import dev.supermux.session.moveId
import dev.supermux.session.sectionKey

data class SessionReorderScope(
    val project: String,
    val section: SectionKey,
)

data class SessionReorderMove(
    val scope: SessionReorderScope,
    val orderedIds: List<String>,
)

enum class SessionSwipeAction {
    Mute,
    Unmute,
    Settle,
    Edit,
    Discard,
    Activate,
}

data class SessionSwipeActions(
    val start: SessionSwipeAction?,
    val end: SessionSwipeAction?,
)

fun reorderScope(session: SessionInfo) = SessionReorderScope(
    project = session.repo_root ?: session.workdir,
    section = session.sectionKey(),
)

fun moveWithinScope(
    rows: List<SessionInfo>,
    workingOrders: Map<SessionReorderScope, List<String>>,
    fromId: String,
    toId: String,
): SessionReorderMove? {
    val from = rows.firstOrNull { it.id == fromId } ?: return null
    val to = rows.firstOrNull { it.id == toId } ?: return null
    val scope = reorderScope(from)
    if (reorderScope(to) != scope) return null
    val ids = workingOrders[scope]
        ?: rows.filter { reorderScope(it) == scope }.map { it.id }
    val fromIndex = ids.indexOf(fromId)
    val toIndex = ids.indexOf(toId)
    if (fromIndex < 0 || toIndex < 0 || fromIndex == toIndex) return null
    return SessionReorderMove(scope, moveId(ids, fromIndex, toIndex))
}

fun applyWorkingOrders(
    rows: List<SessionInfo>,
    workingOrders: Map<SessionReorderScope, List<String>>,
): List<SessionInfo> {
    val byId = rows.associateBy { it.id }
    val queues = rows.groupBy(::reorderScope).mapValues { (scope, scopedRows) ->
        (workingOrders[scope] ?: scopedRows.map { it.id }).mapNotNull(byId::get).iterator()
    }
    return rows.map { row -> queues.getValue(reorderScope(row)).next() }
}

fun sessionSwipeActions(session: SessionInfo): SessionSwipeActions = when (session.sectionKey()) {
    SectionKey.IN_PROGRESS -> SessionSwipeActions(
        start = if (session.mute == true) SessionSwipeAction.Unmute else SessionSwipeAction.Mute,
        end = SessionSwipeAction.Settle,
    )
    SectionKey.DRAFT -> SessionSwipeActions(SessionSwipeAction.Edit, SessionSwipeAction.Discard)
    SectionKey.SETTLED -> SessionSwipeActions(SessionSwipeAction.Activate, null)
}
```

- [ ] **Step 4: Run the focused test and verify GREEN**

Run the Task 1 Gradle command again.

Expected: `SessionListInteractionsTest` passes.

- [ ] **Step 5: Commit the pure interaction model**

```bash
git add apps/android/src/main/kotlin/dev/supermux/android/session/SessionListInteractions.kt \
  apps/android/src/test/kotlin/dev/supermux/android/session/SessionListInteractionsTest.kt
git commit -m "test(android): specify session list interactions"
```

### Task 2: Build a reveal-then-tap swipe shell

**Files:**
- Create: `apps/android/src/main/kotlin/dev/supermux/android/session/SwipeActionRow.kt`
- Create: `apps/android/src/androidTest/kotlin/dev/supermux/android/session/SwipeActionRowTest.kt`
- Modify: `apps/android/build.gradle.kts`

- [ ] **Step 1: Add Compose UI-test dependencies**

Add to `dependencies` in `apps/android/build.gradle.kts`:

```kotlin
androidTestImplementation(platform(libs.compose.bom))
androidTestImplementation("androidx.compose.ui:ui-test-junit4")
debugImplementation("androidx.compose.ui:ui-test-manifest")
```

- [ ] **Step 2: Write failing reveal-action UI tests**

Create `SwipeActionRowTest.kt` with:

```kotlin
package dev.supermux.android.session

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.dp
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test

class SwipeActionRowTest {
@get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

@Test
fun swipeOnlyRevealsAndButtonTapExecutes() {
    var calls = 0
    var openId by mutableStateOf<String?>(null)
    compose.setContent {
        MaterialTheme {
            SwipeActionRow(
                rowId = "one",
                openRowId = openId,
                onOpenRowChange = { openId = it },
                startLabel = "Mute",
                endLabel = "Settle",
                onStartAction = { calls++ },
                onEndAction = { calls++ },
            ) { Box(Modifier.fillMaxWidth().height(72.dp).testTag("row")) }
        }
    }
    compose.onNodeWithTag("row").performTouchInput { swipeRight() }
    compose.runOnIdle { assertEquals(0, calls) }
    compose.onNodeWithText("Mute").assertIsDisplayed().performClick()
    compose.runOnIdle { assertEquals(1, calls) }
}

@Test
fun openingSecondRowClosesFirstRow() {
    var openId by mutableStateOf<String?>(null)
    compose.setContent {
        MaterialTheme {
            Column {
                SwipeActionRow(
                    rowId = "one",
                    openRowId = openId,
                    onOpenRowChange = { openId = it },
                    startLabel = "Mute one",
                    endLabel = null,
                    onStartAction = {},
                    onEndAction = {},
                ) { Box(Modifier.fillMaxWidth().height(72.dp).testTag("row-one")) }
                SwipeActionRow(
                    rowId = "two",
                    openRowId = openId,
                    onOpenRowChange = { openId = it },
                    startLabel = "Mute two",
                    endLabel = null,
                    onStartAction = {},
                    onEndAction = {},
                ) { Box(Modifier.fillMaxWidth().height(72.dp).testTag("row-two")) }
            }
        }
    }
    compose.onNodeWithTag("row-one").performTouchInput { swipeRight() }
    compose.onNodeWithText("Mute one").assertIsDisplayed()
    compose.onNodeWithTag("row-two").performTouchInput { swipeRight() }
    compose.waitForIdle()
    compose.onNodeWithText("Mute one").assertIsNotDisplayed()
    compose.onNodeWithText("Mute two").assertIsDisplayed()
}
}
```

- [ ] **Step 3: Run the instrumentation test and verify RED**

Run:

```bash
cd apps && ./gradlew :android:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=dev.supermux.android.session.SwipeActionRowTest
```

Expected: compilation fails because `SwipeActionRow` does not exist.

- [ ] **Step 4: Implement `SwipeActionRow`**

Create `SwipeActionRow.kt` with this interface and implementation:

```kotlin
package dev.supermux.android.session

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.consume
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
fun SwipeActionRow(
    rowId: String,
    openRowId: String?,
    onOpenRowChange: (String?) -> Unit,
    startLabel: String?,
    endLabel: String?,
    onStartAction: () -> Unit,
    onEndAction: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val revealPx = with(LocalDensity.current) { 96.dp.toPx() }
    val offset = remember(rowId) { Animatable(0f) }

    LaunchedEffect(openRowId) {
        if (openRowId != rowId && offset.value != 0f) offset.animateTo(0f)
    }

    Box(modifier) {
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            if (startLabel != null) {
                TextButton(
                    modifier = Modifier.width(96.dp),
                    onClick = {
                        onOpenRowChange(null)
                        onStartAction()
                    },
                ) { Text(startLabel) }
            }
        }
        Row(
            Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End,
        ) {
            if (endLabel != null) {
                TextButton(
                    modifier = Modifier.width(96.dp),
                    onClick = {
                        onOpenRowChange(null)
                        onEndAction()
                    },
                ) { Text(endLabel) }
            }
        }
        Box(
            Modifier
                .graphicsLayer { translationX = offset.value }
                .pointerInput(rowId, enabled, startLabel, endLabel) {
                    if (!enabled) return@pointerInput
                    detectHorizontalDragGestures(
                        onHorizontalDrag = { change, delta ->
                            change.consume()
                            val min = if (endLabel == null) 0f else -revealPx
                            val max = if (startLabel == null) 0f else revealPx
                            scope.launch { offset.snapTo((offset.value + delta).coerceIn(min, max)) }
                        },
                        onDragEnd = {
                            val target = when {
                                offset.value > revealPx * .35f && startLabel != null -> revealPx
                                offset.value < -revealPx * .35f && endLabel != null -> -revealPx
                                else -> 0f
                            }
                            onOpenRowChange(if (target == 0f) null else rowId)
                            scope.launch { offset.animateTo(target) }
                        },
                        onDragCancel = { scope.launch { offset.animateTo(0f) } },
                    )
                },
        ) { content() }
    }
}
```

Adjust imports to the exact Compose API version if compilation identifies a renamed symbol; do not change the reveal-only behavior.

- [ ] **Step 5: Run the focused instrumentation tests and verify GREEN**

Run the Task 2 instrumentation command again.

Expected: both `SwipeActionRowTest` tests pass.

- [ ] **Step 6: Commit the tested swipe shell**

```bash
git add apps/android/build.gradle.kts \
  apps/android/src/main/kotlin/dev/supermux/android/session/SwipeActionRow.kt \
  apps/android/src/androidTest/kotlin/dev/supermux/android/session/SwipeActionRowTest.kt
git commit -m "feat(android): reveal session actions before execution"
```

### Task 3: Make flat-list drag state synchronous and persist once

**Files:**
- Modify: `apps/android/src/main/kotlin/dev/supermux/android/session/SessionListScreen.kt`
- Test: `apps/android/src/test/kotlin/dev/supermux/android/session/SessionListInteractionsTest.kt`

- [ ] **Step 1: Add a failing drop-commit controller test**

Extend the unit test with:

```kotlin
@Test
fun dragWorkingState_commitsChangedOrderOnce() {
    val scope = SessionReorderScope("/p", SectionKey.IN_PROGRESS)
    val state = SessionDragWorkingState()
    state.begin(scope, listOf("a", "b"))
    state.move(listOf("b", "a"))
    assertEquals(SessionReorderMove(scope, listOf("b", "a")), state.finish(commit = true))
    assertNull(state.finish(commit = true))
}

@Test
fun dragWorkingState_cancelDoesNotCommit() {
    val scope = SessionReorderScope("/p", SectionKey.IN_PROGRESS)
    val state = SessionDragWorkingState()
    state.begin(scope, listOf("a", "b"))
    state.move(listOf("b", "a"))
    assertNull(state.finish(commit = false))
    assertNull(state.finish(commit = true))
}
```

- [ ] **Step 2: Run the focused unit test and verify RED**

Run the Task 1 Gradle command.

Expected: compilation fails because `SessionDragWorkingState` does not exist.

- [ ] **Step 3: Implement `SessionDragWorkingState` in the pure model**

Add to `SessionListInteractions.kt`:

```kotlin
class SessionDragWorkingState {
    private var scope: SessionReorderScope? = null
    private var original: List<String> = emptyList()
    private var current: List<String> = emptyList()

    fun begin(scope: SessionReorderScope, orderedIds: List<String>) {
        this.scope = scope
        original = orderedIds
        current = orderedIds
    }

    fun move(orderedIds: List<String>) {
        if (scope != null) current = orderedIds
    }

    fun finish(commit: Boolean): SessionReorderMove? {
        val finishedScope = scope
        val result = if (
            commit && finishedScope != null && current != original
        ) SessionReorderMove(finishedScope, current) else null
        scope = null
        original = emptyList()
        current = emptyList()
        return result
    }
}
```

- [ ] **Step 4: Run the focused unit test and verify GREEN**

Run the Task 1 Gradle command.

Expected: all interaction-model tests pass.

- [ ] **Step 5: Wire flat rows to local working order**

In `SessionListScreen`:

- hold `mutableStateMapOf<SessionReorderScope, List<String>>()`;
- derive displayed flat rows through `applyWorkingOrders`;
- make the reorder callback call `moveWithinScope` and synchronously update the map;
- call `dragState.begin` from `longPressDraggableHandle(onDragStarted = ...)`;
- close any open swipe tray on drag start;
- call `dragState.finish(true)` from `onDragStopped` and invoke `onReorder` once;
- remove working state after persistence;
- use `SwipeActionRow` around the row and map explicit button taps to existing callbacks.

- [ ] **Step 6: Run unit tests and assemble Android**

```bash
cd apps && ./gradlew :android:testDebugUnitTest :android:assembleDebug
```

Expected: unit tests pass and `apps/android/build/outputs/apk/debug/android-debug.apk` is produced.

- [ ] **Step 7: Commit flat drag integration**

```bash
git add apps/android/src/main/kotlin/dev/supermux/android/session/SessionListInteractions.kt \
  apps/android/src/test/kotlin/dev/supermux/android/session/SessionListInteractionsTest.kt \
  apps/android/src/main/kotlin/dev/supermux/android/session/SessionListScreen.kt
git commit -m "fix(android): update session order during drag"
```

### Task 4: Flatten grouped rows into the reorderable lazy list

**Files:**
- Modify: `apps/android/src/main/kotlin/dev/supermux/android/session/SessionListScreen.kt`

- [ ] **Step 1: Replace each grouped `Column` item with top-level lazy items**

Emit:

- one top-level project header item;
- top-level session-row items for each open non-settled section;
- one settled-toggle item;
- top-level settled rows when expanded.

Use keys of the form `group:<project>:<section>:<sessionId>`. Wrap eligible in-progress and draft rows in `ReorderableItem`; leave personal assistants and settled rows non-reorderable.

- [ ] **Step 2: Preserve grouped-card visuals**

Pass a row-position enum (`Single`, `Top`, `Middle`, `Bottom`) into `SessionRow`. Derive it from the visible rows in the group, apply matching rounded-corner shapes, and render dividers between top-level rows so the result remains visually equivalent to the old nested card.

- [ ] **Step 3: Share the same drag and swipe state**

Use the Task 3 working-order map and drag controller for grouped rows. Reject cross-project and cross-section targets through `moveWithinScope`. Observe `listState.isScrollInProgress` and close the open swipe tray when vertical scrolling starts.

- [ ] **Step 4: Build and run all Android tests**

```bash
cd apps && ./gradlew :android:testDebugUnitTest :android:assembleDebug
```

Expected: all tests pass and the debug APK assembles.

- [ ] **Step 5: Commit grouped drag integration**

```bash
git add apps/android/src/main/kotlin/dev/supermux/android/session/SessionListScreen.kt
git commit -m "fix(android): reorder sessions in grouped view"
```

### Task 5: Emulator verification and final cleanup

**Files:**
- Modify only files already listed if verification exposes a defect.

- [ ] **Step 1: Install the current debug APK**

```bash
adb -s emulator-5554 install -r apps/android/build/outputs/apk/debug/android-debug.apk
adb -s emulator-5554 shell am start -n dev.supermux.android/.MainActivity
```

- [ ] **Step 2: Verify flat mode**

With “Group by project” off:

- long-press a row, drag across another row in the same scope, and confirm neighbors animate;
- release and confirm the order remains after refresh;
- swipe each direction and confirm buttons remain revealed;
- confirm no action runs until its button is tapped;
- confirm settle/discard still open confirmation.

- [ ] **Step 3: Verify grouped mode and edge scrolling**

With “Group by project” on:

- reorder two rows within one project/status section;
- confirm dragging cannot cross project or status boundaries;
- drag near the viewport edge and confirm auto-scroll;
- confirm the grouped card corners and dividers remain correct.

- [ ] **Step 4: Run the complete verification set**

```bash
cd apps && ./gradlew :android:testDebugUnitTest :android:connectedDebugAndroidTest :android:assembleDebug
cd .. && git diff --check && git status --short
```

Expected: all Gradle tasks pass, `git diff --check` prints nothing, and status contains only intentional changes.

- [ ] **Step 5: Commit any verification-only corrections**

If Step 2 or 3 required corrections, commit only those files:

```bash
git add apps/android/src/main/kotlin/dev/supermux/android/session \
  apps/android/src/test/kotlin/dev/supermux/android/session \
  apps/android/src/androidTest/kotlin/dev/supermux/android/session
git commit -m "fix(android): polish session list gestures"
```
