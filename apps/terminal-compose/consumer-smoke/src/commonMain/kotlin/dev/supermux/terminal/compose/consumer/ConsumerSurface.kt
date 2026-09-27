package dev.supermux.terminal.compose.consumer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.supermux.terminal.TerminalSession
import dev.supermux.terminal.compose.Terminal
import dev.supermux.terminal.compose.TerminalTheme

/**
 * Everything a third-party app needs to put a terminal on screen, in commonMain.
 *
 * It lives in `commonMain` on purpose: compiling it proves the published **metadata** klib is
 * usable from common code, not only from one platform — the same property
 * `terminal-core/consumer-smoke`'s `ConsumerFixture` checks for the engine.
 *
 * Note what is NOT here: no supermux module, no theme adapter, no host framework. The surface takes
 * a session and a `TerminalTheme`, and that is the whole integration.
 */
@Composable
fun ConsumerTerminal(session: TerminalSession, tag: String = TAG) {
    // The tag goes on the TERMINAL's own modifier, not on a wrapper: the surface publishes its
    // rows as semantics on the node it lays out, and an ancestor Box does not merge them. That is
    // also how a host finds a terminal in its own UI tests, which makes this the honest recipe.
    Box(Modifier.size(WIDTH_DP.dp, HEIGHT_DP.dp)) {
        Terminal(
            session = session,
            modifier = Modifier.size(WIDTH_DP.dp, HEIGHT_DP.dp).testTag(tag),
            theme = TerminalTheme(),
        )
    }
}

const val TAG: String = "consumer-terminal"
const val WIDTH_DP: Int = 640
const val HEIGHT_DP: Int = 320
