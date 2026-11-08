package dev.supermux.webview.engine.android

import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.supermux.webview.WebContent
import dev.supermux.webview.WebError
import dev.supermux.webview.engine.EngineEvent
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Loading, commits and paints (Task 12), on the phone. */
@RunWith(AndroidJUnit4::class)
class EngineLoadTest {
    @Test fun anHtmlPageCommitsOnItsOwnOriginAndThenPaints() = EngineHarness.run {
        val page = TestPages.solid("#00ff00")
        assertEquals(View.INVISIBLE, main { webView.visibility }, "invisible until its first document paints")
        main {
            configure(setupFor(page))
            load(page)
        }
        val commit = await("the commit") { it is EngineEvent.UrlChanged } as EngineEvent.UrlChanged
        assertEquals(page.origin.url, commit.url)
        val history = await("the history") { it is EngineEvent.HistoryChanged } as EngineEvent.HistoryChanged
        assertFalse(history.canGoBack)
        await("the first paint") { it == EngineEvent.FirstPaint }
        assertEquals(View.VISIBLE, main { webView.visibility })
        seenOrAwait("the title") { it == EngineEvent.TitleChanged("solid #00ff00") }
    }

    @Test fun aBundleServesItsFilesAndRefusesMissingOnesAndTraversal() = EngineHarness.run {
        loadAndPaint(TestPages.bundle)
        seenOrAwait("the statuses") { it == EngineEvent.TitleChanged("statuses 404,404") }
    }

    @Test fun aUrlPageLoadsFromTheNetwork() = EngineHarness.run {
        loadAndPaint(WebContent.Url("https://example.com/"))
        seenOrAwait("example.com's title") { it == EngineEvent.TitleChanged("Example Domain") }
    }

    @Test fun anInvalidHostNeverReachesTheNetwork() = EngineHarness.run {
        val nowhere = WebContent.Url("https://nowhere.invalid/")
        main {
            configure(setupFor(nowhere))
            load(nowhere)
        }
        val error = await("the error") { it is EngineEvent.Error } as EngineEvent.Error
        assertEquals(WebError.Http("https://nowhere.invalid/", 404), error.error)
    }

    @Test fun nothingOfAnEarlierDocumentIsHeardOnceALoadReturns() = EngineHarness.run {
        loadAndPaint(TestPages.restless)
        await("a restless title") { (it as? EngineEvent.TitleChanged)?.title?.startsWith("A ") == true }
        val next = TestPages.solid("#0000ff", title = "B")
        val mark = loadMarked(next)
        await("B's first paint") { it == EngineEvent.FirstPaint }
        expectNone("an old title", millis = 500) { (it as? EngineEvent.TitleChanged)?.title?.startsWith("A ") == true }
        val afterLoad = seen.drop(mark)
        assertTrue(afterLoad.none { (it as? EngineEvent.TitleChanged)?.title?.startsWith("A ") == true }, "heard A after the load: $afterLoad")
        assertTrue(afterLoad.contains(EngineEvent.UrlChanged(next.origin.url)))
    }
}
