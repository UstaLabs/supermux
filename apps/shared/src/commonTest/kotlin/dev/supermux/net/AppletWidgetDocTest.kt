package dev.supermux.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppletWidgetDocTest {
    private val doc = Json.parseToJsonElement(
        """
        {"v":2,"href":"/#board","layouts":{
          "small":[{"type":"header","title":"Issues"},{"type":"stat","value":"2","label":"to test","tone":"warn"},{"type":"future-thing","x":1}],
          "medium":[{"type":"columns","w":[0.4,0.6],"cols":[[{"type":"stat","value":2}],[{"type":"list","title":"Waiting","rows":[
            {"title":"#164 Resize","sub":"bug","color":"#ff9500","href":"/#164","button":{"title":"Tested","action":"tested","args":{"id":164}}}]}]]}],
          "large":[{"type":"html","part":"board","height":70,"label":"Board","caption":"Board","alt":{"type":"chart","kind":"bars","points":[1,2,3]}},
                   {"type":"toggles","grid":true,"items":[{"title":"Ride","on":false,"action":"toggle","args":{"id":"ride"}},{"title":"x"}]}],
          "xlarge":[{"type":"gap"}],"wide":[{"type":"grow"}],"tall":[{"type":"text","text":"t"}],"slim":[{"type":"ring","value":2,"text":"2/4"}],
          "circular":{"text":"2","ring":0.5,"label":"test","action":{"action":"tested","args":{"id":164}}},
          "rectangular":{"lines":["Issues","2 waiting"],"button":{"title":"Tested #164","action":"tested","args":{"id":164}}},
          "inline":"2 to test"},
         "timeline":[{"at":"2026-11-27T00:00:00.000Z","layouts":{"small":[{"type":"stat","value":"0"}]}}]}
        """.trimIndent(),
    )

    @Test fun parses_every_block_and_skips_unknown_ones() {
        val d = WidgetDoc.parse(doc)!!
        assertEquals("/#board", d.href)
        val small = d.layout(WidgetSize.Small)
        assertEquals(2, small.size)
        assertEquals(WidgetBlock.Stat("2", "to test", null, "warn", null), small[1])
        val cols = d.layout(WidgetSize.Medium)[0] as WidgetBlock.Columns
        assertEquals(listOf(0.4f, 0.6f), cols.weights)
        assertEquals("2", (cols.cols[0][0] as WidgetBlock.Stat).value)
        val row = (cols.cols[1][0] as WidgetBlock.ListRows).rows[0]
        assertEquals("tested", row.button!!.action)
        assertEquals("164", row.button!!.args!!["id"].toString())
        val pic = d.layout(WidgetSize.Large)[0] as WidgetBlock.Picture
        assertEquals("board", pic.part); assertEquals(70, pic.height); assertTrue(pic.alt is WidgetBlock.Chart)
        assertEquals(1, (d.layout(WidgetSize.Large)[1] as WidgetBlock.Toggles).items.size)
        assertEquals(1f, (d.layout(WidgetSize.Slim)[0] as WidgetBlock.Ring).value)
        assertEquals("tested", d.circular.action!!.action)
        assertEquals("Tested #164", d.rectangular.button!!.title)
        assertEquals("2 to test", d.inline)
        assertEquals(listOf(pic), d.pictures(WidgetSize.Large))
    }

    @Test fun the_timeline_takes_over_from_its_time() {
        val d = WidgetDoc.parse(doc)!!
        val at = 1_795_737_600_000L // 2026-11-27T00:00:00Z
        assertEquals("2", (d.layout(WidgetSize.Small, at - 1)[1] as WidgetBlock.Stat).value)
        assertEquals("0", (d.layout(WidgetSize.Small, at)[0] as WidgetBlock.Stat).value)
        assertEquals(WidgetBlock.Ring(1f, "2/4", null), d.layout(WidgetSize.Slim, at)[0])
        assertEquals(listOf(at), d.changesAfter(at - 1))
    }

    @Test fun not_a_document_is_null() {
        assertNull(WidgetDoc.parse(null))
        assertNull(WidgetDoc.parse(Json.parseToJsonElement("""{"value":"3"}""")))
        assertNull(WidgetDoc.parse(Json.parseToJsonElement("""{"layouts":{}}""")))
    }

    @Test fun cells_map_to_sizes_like_the_prototype() {
        assertEquals(WidgetSize.Slim, WidgetSize.forCells(2, 1))
        assertEquals(WidgetSize.Wide, WidgetSize.forCells(4, 1))
        assertEquals(WidgetSize.Small, WidgetSize.forCells(2, 2))
        assertEquals(WidgetSize.Small, WidgetSize.forCells(3, 2))
        assertEquals(WidgetSize.Medium, WidgetSize.forCells(4, 2))
        assertEquals(WidgetSize.Tall, WidgetSize.forCells(2, 4))
        assertEquals(WidgetSize.Large, WidgetSize.forCells(4, 4))
    }

    @Test fun pictures_survive_a_new_snapshot_of_the_same_applet() {
        val w = WidgetApplet("habits", "Habits", widget = doc.jsonObject, widgetHtml = "/a/habits/widget.html")
        val drawn = w.copy(pictures = mapOf("large/heat/dark" to "pic-1.png"))
        assertEquals(drawn.pictures, listOf(w).keepPictures(listOf(drawn))[0].pictures)
        assertEquals(emptyMap(), listOf(w.copy(widgetHtml = null)).keepPictures(listOf(drawn))[0].pictures)
        assertEquals("pic-${widgetFileKey("h/habits/large/heat/dark")}.png", widgetPictureFile("h", "habits", WidgetSize.Large, "heat", true))
    }
}
