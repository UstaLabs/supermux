package dev.supermux.webview.sample

import androidx.compose.ui.unit.dp
import dev.supermux.webview.sample.chat.AgentMessage
import dev.supermux.webview.sample.chat.PreviewAssets
import dev.supermux.webview.sample.chat.PreviewSpec
import dev.supermux.webview.sample.chat.Previews
import dev.supermux.webview.sample.chat.UserMessage
import dev.supermux.webview.sample.chat.chatScript
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The chat demo's model: as long as asked, a page per preview, and every page bundled with the variant it shows. */
class ChatScriptTest {
    @Test fun theChatIsAsLongAsAskedAndEveryPreviewIsAPageOfItsOwn() {
        val items = chatScript()
        val messages = items.count { it is UserMessage || it is AgentMessage }
        val answers = items.filterIsInstance<AgentMessage>()
        val previews = answers.flatMap { it.previews }
        assertTrue(messages in 80..120, "$messages messages")
        assertTrue(previews.size in 40..60, "${previews.size} previews")
        assertEquals(previews.size, previews.map { it.content.key }.toSet().size, "each preview is a page of its own")
        assertEquals(Previews.all.map { it.slug }.toSet(), previews.map { it.slug }.toSet(), "the catalog is what the chat shows")
        val fixed = previews.mapNotNull { it.height }
        assertTrue(fixed.all { it in 180.dp..320.dp }, "fixed heights: $fixed")
        assertTrue(previews.count { it.height == null } >= 10, "wrap-content previews: ${previews.count { it.height == null }}")
        assertTrue(answers.any { it.previews.size == 2 }, "an answer with a second preview")
        assertTrue(answers.any { it.text.startsWith("${previews.size}, across $messages messages") }, "the agent counts right")
    }

    /**
     * A wrap-content preview's first height is its page's on the card it is shown on (2026-11-05: the guesses were a
     * phone's, and on the Mac's 680 dp cards 13 of 20 pages then resized on screen, by up to 83 dp). Between a phone's
     * card and the mid width, where the pages change from one height to the other, it is the larger of the two.
     */
    @Test fun aWrapContentPreviewsFirstHeightFollowsItsCardsWidth() {
        val wrapped = Previews.all.filter { it.height == null }
        for (spec in wrapped) {
            assertEquals(spec.initial, spec.initialFor(329.dp), "${spec.slug} on an iPhone 15 Pro's card")
            assertEquals(maxOf(spec.initial, spec.initialMid), spec.initialFor(360.dp), "${spec.slug} between a phone's and the mid width")
            assertEquals(spec.initialMid, spec.initialFor(PreviewSpec.MID_WIDTH), "${spec.slug} from the mid width")
            assertEquals(spec.initialWide, spec.initialFor(680.dp), "${spec.slug} on a desktop's or a tablet's card")
            assertTrue(spec.initialFor(680.dp) <= PreviewSpec.MAX_WRAP && spec.initial <= PreviewSpec.MAX_WRAP, "${spec.slug} within its cap")
        }
        // The pages measured with a wide layout of their own: the form's two columns, the loaders' one row.
        assertEquals(439.dp, Previews.form.initialFor(680.dp))
        assertEquals(241.dp, Previews.loadersViolet.initialFor(680.dp))
        assertEquals(515.dp, Previews.form.initialFor(329.dp))
    }

    @Test fun everyPreviewIsBundledWithItsVariant() {
        runBlocking {
            for (spec in Previews.all) {
                val page = assertNotNull(spec.content.resources.load(spec.entry), "${spec.template}.html is bundled")
                val html = page.bytes.decodeToString()
                assertTrue("window.PREVIEW_VARIANT=\"${spec.variant}\"" in html, "${spec.slug}: the variant is written into the page")
                if (spec.variant != "default") {
                    // A presets map (`rose: {…}`) or a branch on the name (`Kit.variant('note') === 'live'`).
                    val named = Regex("""\b${spec.variant}\s*:|['"]${spec.variant}['"]""")
                    assertTrue(named.containsMatchIn(html), "${spec.template}.html has a ${spec.variant} variant")
                }
                assertTrue("lib/kit.js" in html, "${spec.template}.html loads the kit")
                val sizing = if (spec.height == null) "class=\"wrap" else "class=\"fixed"
                assertTrue(sizing in html, "${spec.slug} is a ${if (spec.height == null) "wrap-content" else "fixed-height"} page")
                if (spec.height == null) {
                    // A page that grows with its own viewport would grow the view it sizes.
                    assertNull(Regex("""\d(\.\d+)?vh\b""").find(html)?.value, "${spec.slug}: no vh units in a wrap-content page")
                }
            }
            for (file in listOf("lib/kit.js", "lib/kit.css", "lib/three.module.min.js", "lib/three.core.min.js")) {
                val shared = assertNotNull(PreviewAssets.shared(file), file)
                if (file.startsWith("lib/three")) assertTrue(shared.bytes.decodeToString(0, 200).contains("@license"), "$file keeps its licence header")
            }
            assertNull(Previews.galaxyClassic.content.resources.load("secrets.txt"), "nothing else is served")
        }
    }
}
