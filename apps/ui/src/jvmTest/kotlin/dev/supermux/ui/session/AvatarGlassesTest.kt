package dev.supermux.ui.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AvatarGlassesTest {
    private fun rig(eyes: String) = assertNotNull(
        parseAvatarRig(
            """{"format":"supermux-avatar","version":2,"size":100,"parts":[{"id":"head","shape":"group","children":[
                {"id":"face","shape":"ellipse","cx":50,"cy":55,"rx":30,"ry":28,"fill":"#FFE0CC"},
                $eyes,
                {"id":"mouth","d":"M44 70 Q50 74 56 70","fill":"none","stroke":"#000000"}]}],
                "states":{"idle":{"tracks":[]}}}""",
        ),
    )

    private fun find(parts: List<RigPart>, id: String): RigPart? {
        for (p in parts) {
            if (p.id == id) return p
            find(p.children, id)?.let { return it }
        }
        return null
    }

    private fun parentOf(parts: List<RigPart>, id: String): RigPart? {
        for (p in parts) {
            if (p.children.any { it.id == id }) return p
            parentOf(p.children, id)?.let { return it }
        }
        return null
    }

    private val roundEyes = """{"id":"eyes","shape":"group","behaviors":[{"type":"blink"}],"children":[
        {"id":"eyeL","shape":"ellipse","cx":38,"cy":50,"rx":5,"ry":6,"fill":"#000000"},
        {"id":"eyeR","shape":"ellipse","cx":62,"cy":50,"rx":5,"ry":6,"fill":"#000000"}]}"""

    @Test
    fun glassesSitAroundBothEyesAndDoNotBlink() {
        val worn = assertNotNull(rig(roundEyes).wearingGlasses())
        // Beside the eyes, not in their group: they move with the head, but don't blink with the eyes.
        assertEquals("head", parentOf(worn.parts, "glasses")?.id)
        val l = assertNotNull(find(worn.parts, "lensL"))
        val r = assertNotNull(find(worn.parts, "lensR"))
        assertEquals(38f, l.n("cx", 0f))
        assertEquals(50f, l.n("cy", 0f))
        assertEquals(62f, r.n("cx", 0f))
        assertTrue(l.n("rx", 0f) > 5f && l.n("ry", 0f) > 6f, "a lens is bigger than the eye it covers")
    }

    // Anya's eyes: a group each, with the white the biggest round part.
    @Test
    fun anEyeDrawnAsAGroupIsMeasuredByItsWhite() {
        val worn = assertNotNull(
            rig(
                """{"id":"eyes","shape":"group","behaviors":[{"type":"blink"}],"children":[
                {"id":"eyeL","shape":"group","children":[{"id":"whiteL","shape":"ellipse","cx":37,"cy":59.5,"rx":7.4,"ry":7.6},
                  {"id":"irisL","shape":"group","behaviors":[{"type":"lookAt"}],"children":[{"id":"irisCL","shape":"ellipse","cx":37,"cy":59.5,"rx":6.2,"ry":6.6}]}]},
                {"id":"eyeR","shape":"group","children":[{"id":"whiteR","shape":"ellipse","cx":63,"cy":59.5,"rx":7.4,"ry":7.6}]}]}""",
            ).wearingGlasses(),
        )
        val l = assertNotNull(find(worn.parts, "lensL"))
        assertEquals(37f, l.n("cx", 0f))
        assertEquals(59.5f, l.n("cy", 0f))
        assertTrue(l.n("rx", 0f) > 7.4f)
        assertEquals(63f, find(worn.parts, "lensR")?.n("cx", 0f))
    }

    // AgentOS's eyes are paths.
    @Test
    fun anEyeDrawnAsAPathIsMeasuredByItsBounds() {
        val worn = assertNotNull(
            rig(
                """{"id":"eyes","shape":"group","children":[
                {"id":"eyeL","d":"M30 40 L38 40 L38 50 L30 50 Z"},{"id":"eyeR","d":"M62 40 L70 40 L70 50 L62 50 Z"}]}""",
            ).wearingGlasses(),
        )
        val l = assertNotNull(find(worn.parts, "lensL"))
        assertEquals(34f, l.n("cx", 0f))
        assertEquals(45f, l.n("cy", 0f))
        assertEquals(66f, find(worn.parts, "lensR")?.n("cx", 0f))
    }

    @Test
    fun lensesNeverOverlap() {
        val worn = assertNotNull(
            rig(
                """{"id":"eyes","shape":"group","children":[
                {"id":"eyeL","shape":"ellipse","cx":44,"cy":50,"rx":5,"ry":5},{"id":"eyeR","shape":"ellipse","cx":56,"cy":50,"rx":5,"ry":5}]}""",
            ).wearingGlasses(),
        )
        val l = assertNotNull(find(worn.parts, "lensL"))
        val r = assertNotNull(find(worn.parts, "lensR"))
        assertTrue(l.n("cx", 0f) + l.n("rx", 0f) < r.n("cx", 0f) - r.n("rx", 0f))
    }

    @Test
    fun withoutEyesThereAreNoGlasses() {
        assertNull(rig("""{"id":"visor","shape":"ellipse","cx":50,"cy":50,"rx":20,"ry":6}""").wearingGlasses())
    }

    @Test
    fun theAvatarItselfIsLeftAsItWas() {
        val plain = rig(roundEyes)
        assertNotNull(plain.wearingGlasses())
        assertNull(find(plain.parts, "glasses"))
    }
}
