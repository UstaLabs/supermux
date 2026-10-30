package dev.supermux.webview.layouttest

import java.nio.file.Files
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReportTest {
    @Test fun resultsGoToTheSummaryAndTheJson() {
        val dir = Files.createTempDirectory("layout-report").toFile()
        val report = Report(dir)
        report.note("macOS 26.6.2")
        report.add("A", "clicks", "menu-item", Verdict.PASS, "expected Compose, got Compose")
        report.add("B", "frames", "list-scroll-page", Verdict.FAIL, "p95=40.0ms")
        report.finish()
        val lines = dir.resolve("summary.txt").readLines()
        assertEquals("NOTE macOS 26.6.2", lines[0])
        assertEquals("RESULT A clicks menu-item PASS expected Compose, got Compose", lines[1])
        assertEquals("LAYOUT-TEST DONE", lines.last())
        val json = dir.resolve("results.json").readText()
        assertTrue(""""layout":"B","criterion":"frames","name":"list-scroll-page","verdict":"FAIL"""" in json, json)
    }

    @Test fun offCapturesNothing() {
        val dir = Files.createTempDirectory("layout-shots").toFile()
        assertNull(Screenshots(ShotMode.Off, dir).capture("rest", 4711))
    }

    @Test fun externalWaitsForTheLoopToAnswer() {
        val dir = Files.createTempDirectory("layout-shots").toFile()
        val loop = thread {
            val request = dir.resolve("rest.req")
            while (!request.exists()) Thread.sleep(10)
            assertEquals("4711", request.readText())
            dir.resolve("rest.png").writeBytes(byteArrayOf(1, 2, 3))
            request.delete()
        }
        val shot = Screenshots(ShotMode.External, dir).capture("rest", 4711)
        loop.join()
        assertEquals(dir.resolve("rest.png"), shot)
    }
}
