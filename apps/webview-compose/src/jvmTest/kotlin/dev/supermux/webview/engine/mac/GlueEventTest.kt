package dev.supermux.webview.engine.mac

import dev.supermux.webview.PermissionKind
import dev.supermux.webview.engine.webkit.NavigationKind
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** The upcalls' fields, as `WVGlue.h` writes them (Task 3's table). */
class GlueEventTest {
    private fun decode(
        type: Int,
        epoch: Int = 0,
        id: Long = 0,
        flags: Int = 0,
        x: Double = 0.0,
        y: Double = 0.0,
        a: String? = null,
        b: String? = null,
        c: String? = null,
        d: String? = null,
        data: ByteArray? = null,
    ) = GlueEvent.decode(type, epoch, id, flags, x, y, a, b, c, d, data)

    @Test fun creationGivesTheHandle() {
        assertEquals(GlueEvent.Created(0x7f00L), decode(GlueEvent.CREATED, id = 0x7f00L))
    }

    @Test fun navigationsCarryTheirEpochAndWebKitsPage() {
        assertEquals(GlueEvent.Started(3, "https://a.test/"), decode(GlueEvent.STARTED, epoch = 3, a = "https://a.test/"))
        val committed = decode(
            GlueEvent.COMMITTED, epoch = 4, flags = GlueEvent.LOADING, x = 0.25,
            a = "https://a.test/2", b = "webview-floor://reset/\nhttps://a.test/1", c = "", d = "Two",
        )
        assertEquals(
            GlueEvent.Committed(
                4,
                WebKitPage("https://a.test/2", "Two", listOf("webview-floor://reset/", "https://a.test/1"), emptyList(), loading = true),
                0.25f,
            ),
            committed,
        )
        val finished = decode(GlueEvent.FINISHED, epoch = 4, flags = GlueEvent.FLOOR_KEPT, a = "webview-floor://reset/")
        assertEquals(GlueEvent.Finished(4, WebKitPage("webview-floor://reset/", null, emptyList(), emptyList(), loading = false), floorKept = true), finished)
        assertEquals(
            GlueEvent.Failed(5, "https://a.test/", "NSURLErrorDomain", -1009, "offline"),
            decode(GlueEvent.FAILED, epoch = 5, x = -1009.0, a = "https://a.test/", b = "NSURLErrorDomain", c = "offline"),
        )
    }

    @Test fun aPolicyQuestionCarriesTheFrameAndTheNavigationType() {
        val policy = decode(GlueEvent.POLICY, id = 9, flags = 1 or 4 or ((2 + 1) shl 8), a = "https://a.test/")
        assertEquals(GlueEvent.Policy(9, "https://a.test/", mainFrame = true, opensWindow = false, download = true, kind = NavigationKind.BackForward), policy)
        val other = decode(GlueEvent.POLICY, id = 10, flags = 2 or ((-1 + 1) shl 8), a = "https://b.test/")
        assertEquals(GlueEvent.Policy(10, "https://b.test/", mainFrame = false, opensWindow = true, download = false, kind = NavigationKind.Other), other)
    }

    @Test fun aResponseCarriesItsStatusOnlyOverHttp() {
        val http = decode(
            GlueEvent.RESPONSE, id = 2, flags = 1 or 2 or 4 or (404 shl 8), x = 1234.0,
            a = "https://a.test/f.pdf", b = "application/pdf", c = "f.pdf", d = "attachment; filename=f.pdf",
        )
        assertEquals(GlueEvent.Response(2, "https://a.test/f.pdf", true, true, 404, 1234L, "application/pdf", "f.pdf", "attachment; filename=f.pdf"), http)
        val local = decode(GlueEvent.RESPONSE, id = 3, flags = 1 or 2, x = -1.0, a = "webview-bundle://p.invalid/")
        assertEquals(GlueEvent.Response(3, "webview-bundle://p.invalid/", true, true, null, null, null, null, null), local)
    }

    @Test fun stateRelayAndQuestionsKeepTheirFields() {
        assertEquals(
            GlueEvent.State(2, WebKitPage("https://a.test/#x", "T", listOf("https://a.test/"), emptyList(), loading = false)),
            decode(GlueEvent.STATE, epoch = 2, a = "https://a.test/#x", b = "https://a.test/", d = "T"),
        )
        assertEquals(
            GlueEvent.Relay(2, true, "0123456789abcdef start", "https", "c1.invalid", 0),
            decode(GlueEvent.RELAY, epoch = 2, flags = 1, a = "0123456789abcdef start", b = "https", c = "c1.invalid"),
        )
        assertEquals(GlueEvent.NewWindow("https://a.test/p"), decode(GlueEvent.NEW_WINDOW, a = "https://a.test/p"))
        assertEquals(
            GlueEvent.Dialog(7, DialogKind.Prompt, "Name?", "Ann", "https://a.test/"),
            decode(GlueEvent.DIALOG, id = 7, flags = 2, a = "Name?", b = "Ann", c = "https://a.test/"),
        )
        assertEquals(
            GlueEvent.Media(8, setOf(PermissionKind.Camera, PermissionKind.Microphone), "https", "a.test", 8443),
            decode(GlueEvent.MEDIA, id = 8, flags = 2, x = 8443.0, a = "https", b = "a.test"),
        )
        assertEquals(GlueEvent.OpenPanel(6, multiple = true, directories = false), decode(GlueEvent.OPEN_PANEL, id = 6, flags = 1))
        assertEquals(GlueEvent.TaskStart(11, "webview-bundle://p.invalid/a.js"), decode(GlueEvent.TASK_START, id = 11, a = "webview-bundle://p.invalid/a.js"))
        assertEquals(GlueEvent.TaskStop(11), decode(GlueEvent.TASK_STOP, id = 11))
    }

    @Test fun resultsSnapshotsTicksAndTheRest() {
        val result = assertIs<GlueEvent.Result>(decode(GlueEvent.RESULT, id = 4, flags = 1, a = "\"ok\"", data = byteArrayOf(1, 2)))
        assertEquals(4, result.request)
        assertEquals(true, result.ok)
        assertEquals("\"ok\"", result.json)
        assertContentEquals(byteArrayOf(1, 2), result.data)
        val snapshot = assertIs<GlueEvent.Snapshot>(decode(GlueEvent.SNAPSHOT, id = 5, x = 640.0, y = 960.0, data = ByteArray(4)))
        assertEquals(640, snapshot.width)
        assertEquals(960, snapshot.height)
        assertEquals(GlueEvent.Tick(6, inWindow = true), decode(GlueEvent.TICK, id = 6, flags = 1))
        assertEquals(GlueEvent.Crashed, decode(GlueEvent.CRASHED))
        assertEquals(GlueEvent.Pointer(12.5, 40.0), decode(GlueEvent.POINTER, x = 12.5, y = 40.0))
        assertEquals(GlueEvent.Memory, decode(GlueEvent.MEMORY))
    }

    @Test fun anUnknownTypeIsDropped() {
        assertNull(decode(0))
        assertNull(decode(99))
    }
}
