package dev.supermux.webview.engine.windows

import dev.supermux.webview.WebContent
import dev.supermux.webview.WebOrigin
import dev.supermux.webview.WebResource
import dev.supermux.webview.WebResourceProvider
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class WindowsWebEngineContentTest {
    private class Files(private val files: Map<String, String>) : WebResourceProvider {
        val asked = mutableListOf<String>()
        override suspend fun load(path: String): WebResource? {
            asked += path
            return files[path]?.let { WebResource(it.encodeToByteArray(), "text/plain", mapOf("X-Test" to "1", "Bad\r\nHeader" to "x")) }
        }
    }

    private val files = Files(mapOf("index.html" to "<p>index</p>", "data.json" to "{}"))
    private val bundle = WebContent.Bundle(WebOrigin("plugin"), files)

    @AfterTest fun quiet() = WinPages.resetForTests()

    @Test fun aBundlesFilesComeFromItsProviderWithTheirHeaders() = runTest {
        val rig = WinRig(this)
        rig.warm()
        rig.show(bundle, "https://plugin.invalid/index.html")
        rig.engine.onEvent(WinEvent.Resource(rig.page.epoch, 7, "https://plugin.invalid/data.json", "GET"))
        val (status, headers, body) = rig.page.responses.getValue(7)
        assertEquals(200, status)
        assertEquals("{}", body?.decodeToString())
        assertTrue(headers!!.startsWith("Content-Type: text/plain"), headers)
        assertTrue("X-Test: 1" in headers && "Bad" !in headers, "a header that can't be one line is left out: $headers")
        rig.close()
    }

    @Test fun pathsThatClimbOutAreA404AndNeverAsked() = runTest {
        val rig = WinRig(this)
        rig.warm()
        rig.show(bundle, "https://plugin.invalid/index.html")
        for ((n, path) in listOf("/%2e%2e%2fsecret", "/..%5csecret", "/a/..%5c..%5csecret", "/x:y", "/name.. ").withIndex()) {
            rig.engine.onEvent(WinEvent.Resource(rig.page.epoch, 10L + n, "https://plugin.invalid$path", "GET"))
            assertEquals(404, rig.page.responses.getValue(10L + n).first, path)
        }
        assertTrue(files.asked.none { ".." in it || '\\' in it || ':' in it }, "${files.asked}")
        rig.close()
    }

    @Test fun onlyGetAndHeadAreServed() = runTest {
        val rig = WinRig(this)
        rig.warm()
        rig.show(bundle, "https://plugin.invalid/index.html")
        rig.engine.onEvent(WinEvent.Resource(rig.page.epoch, 20, "https://plugin.invalid/data.json", "POST"))
        assertEquals(405, rig.page.responses.getValue(20).first)
        rig.close()
    }

    @Test fun anotherHostFailsAsAnUnknownHostDoes() = runTest {
        val rig = WinRig(this)
        rig.warm()
        rig.show(bundle, "https://plugin.invalid/index.html")
        rig.engine.onEvent(WinEvent.Resource(rig.page.epoch, 30, "https://other.invalid/x", "GET"))
        assertEquals(0, rig.page.responses.getValue(30).first, "no response: a missed interception fails closed")
        rig.close()
    }

    @Test fun whatTheProviderHasntAnsweredIsAnsweredAtRelease() = runTest {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val slow = object : WebResourceProvider {
            override suspend fun load(path: String): WebResource? { gate.await(); return null }
        }
        val rig = WinRig(this)
        rig.warm()
        rig.show(WebContent.Bundle(WebOrigin("slow"), slow), "https://slow.invalid/index.html")
        rig.engine.onEvent(WinEvent.Resource(rig.page.epoch, 40, "https://slow.invalid/a.js", "GET"))
        rig.engine.release()
        assertEquals(404, rig.page.responses.getValue(40).first)
        rig.close()
    }
}
