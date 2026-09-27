package dev.supermux.terminal.compose.consumer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.terminal.TerminalSession
import dev.supermux.terminal.TerminalSize
import dev.supermux.terminal.compose.Terminal
import dev.supermux.terminal.compose.TerminalTheme
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Can a stranger draw a terminal with the published pair?
 *
 * Nothing in this build knows about supermux beyond two Maven coordinates, and the coordinates can
 * only be served by the two local test repositories (content filters in `settings.gradle.kts` and
 * `build.gradle.kts`). So these are not "does the code work" tests — the package has 111 of those —
 * they are "is the package usable from outside" tests, and every one of them would still pass if
 * the supermux repository did not exist on this machine.
 */
class PackagedSurfaceTest {

    private val sessions = mutableListOf<TerminalSession>()

    @AfterTest
    fun closeSessions() {
        runBlocking { sessions.forEach { runCatching { it.close() } } }
        sessions.clear()
    }

    private val openedSize = TerminalSize(40, 8, 8, 16)

    private fun openSession(): TerminalSession = runBlocking {
        TerminalSession.open(openedSize).also { sessions += it }
    }

    // ---- where the code came from ---------------------------------------------------------

    @Test
    fun theSurfaceComesFromThePublishedJar() {
        val version = assertNotNull(System.getProperty("consumerSmoke.expectedVersion"))
        // `Terminal` is a top-level @Composable, so it has no `::class` of its own — its bytecode
        // lives in the file facade `TerminalKt`. Loading that BY NAME is also the stronger check:
        // it proves the composable itself is in the published jar, not merely some type beside it.
        val surfaceClass = Class.forName("dev.supermux.terminal.compose.TerminalKt")
        val source = assertNotNull(
            surfaceClass.protectionDomain?.codeSource?.location?.toString(),
            "no code source for the Terminal composable — it was not loaded from a jar",
        )
        assertTrue(
            source.endsWith("terminal-compose-jvm-$version.jar"),
            "the surface was not loaded from the published jar: $source",
        )
        assertTrue(
            source.contains("terminal-compose/build/test-repository"),
            "the jar did not come from the local test repository: $source",
        )
        // The ENGINE arrived transitively, as `api`. A consumer names the surface and gets the
        // engine; that is part of the published contract, not an accident of this build file.
        val engineSource = assertNotNull(
            TerminalSession::class.java.protectionDomain?.codeSource?.location?.toString(),
        )
        assertTrue(
            engineSource.endsWith("terminal-core-jvm-$version.jar"),
            "the engine was not loaded from the published jar: $engineSource",
        )
    }

    @Test
    fun noPartOfSupermuxIsOnTheClasspath() {
        // TRIPWIRE, not proof — Gradle can hand the JVM one synthetic jar whose manifest carries
        // the real Class-Path, and then `java.class.path` shows only that jar. The code-source
        // assertions above do not read the classpath at all and are the real evidence.
        val classpath = System.getProperty("java.class.path").orEmpty().split(File.pathSeparator)
        val forbidden = listOf(
            "terminal-compose/build/classes", "terminal-compose/src",
            "terminal-core/build/gradle", "terminal-core/src", "terminal-core/build/native",
            "/shared/", "/ui/", "supermux-apps",
        )
        val offenders = classpath.filter { entry -> forbidden.any { entry.contains(it) } }
        assertTrue(offenders.isEmpty(), "supermux build output on the consumer classpath: $offenders")
        assertNull(
            System.getProperty("supermux.terminal.nativeLibrary"),
            "the developer library override is set; the packaged native library is the point",
        )
    }

    @Test
    fun theLicenceRidesInsideThePublishedJar() {
        val licence = TerminalTheme::class.java.classLoader
            .getResource("META-INF/dev.supermux.terminal/LICENSE")
        assertNotNull(licence, "the published jar carries no licence")
        assertTrue(licence.toString().startsWith("jar:file:"), "the licence is not a jar resource: $licence")
    }

    // ---- does it actually draw -------------------------------------------------------------

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun theSurfaceDrawsASessionRunOnThePackagedEngine() = runComposeUiTest {
        val session = openSession()
        setContent { ConsumerTerminal(session) }
        // Bytes in through the session's own entry point, exactly as a transport would feed them.
        runBlocking { session.receive("hello from a stranger\r\nsecond line".encodeToByteArray()) }
        waitUntil(timeoutMillis = TIMEOUT) { screenText().contains("hello from a stranger") }
        val text = screenText()
        assertTrue("second line" in text, "the second row never arrived: $text")

        // The native library the engine loaded is the one the PUBLISHED jar extracted, into this
        // build's own cache directory — nothing from terminal-core's build tree is mapped in.
        val cache = assertNotNull(System.getProperty("consumerSmoke.nativeCache"))
        val maps = File("/proc/self/maps")
        if (maps.canRead()) {
            val mapped = maps.readLines()
                .filter { it.contains("supermux_terminal") }
                .map { it.substringAfter(" /", "").let { path -> "/$path" } }
                .distinct()
            assertTrue(mapped.isNotEmpty(), "no supermux_terminal mapping: the engine did not load natively")
            assertTrue(
                mapped.all { it.startsWith(cache) },
                "the engine loaded a library from outside this build's cache: $mapped",
            )
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun aHostThemesTheSurfaceWithoutAnySupermuxType() = runComposeUiTest {
        val session = openSession()
        // The whole theming API: a data class of colours and a font. No app theme, no adapter, no
        // CompositionLocal of ours — which is what "publishable on its own" has to mean in practice.
        val theme = TerminalTheme(
            foreground = Color(0xFFEEEEEE),
            background = Color(0xFF001122),
            fontSize = 11.sp,
        )
        setContent {
            Box(Modifier.size(WIDTH_DP.dp, HEIGHT_DP.dp)) {
                Terminal(
                    session = session,
                    modifier = Modifier.size(WIDTH_DP.dp, HEIGHT_DP.dp).testTag(TAG),
                    theme = theme,
                )
            }
        }
        runBlocking { session.receive("themed\r\n".encodeToByteArray()) }
        waitUntil(timeoutMillis = TIMEOUT) { screenText().contains("themed") }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun theSurfaceReportsTheGridItSettledOn() = runComposeUiTest {
        val session = openSession()
        setContent { ConsumerTerminal(session) }
        // The surface measures the 640x320 dp box and resizes the session to the cells that fit,
        // so the grid MOVES OFF the one the session was opened with. That move is the assertion;
        // the exact numbers depend on the platform's monospace font and are not a contract.
        waitUntil(timeoutMillis = TIMEOUT) { session.viewports.value.size != openedSize }
        val size = session.viewports.value.size
        assertTrue(size.columns > 10 && size.rows > 3, "implausible grid: ${size.columns}x${size.rows}")
        assertTrue(size.cellWidthPx > 0 && size.cellHeightPx > 0, "the surface reported no cell size")
    }

    private companion object {
        const val TIMEOUT = 15_000L
    }
}

/** The visible rows, as the surface publishes them to a screen reader — and to this test. */
@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.screenText(): String =
    onNodeWithTag(TAG).fetchSemanticsNode()
        .config
        .getOrNull(SemanticsProperties.Text)
        ?.joinToString("") { it.text }
        .orEmpty()
