package dev.supermux.fs

import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Card #29, Q12. On a Turkish-language Windows, upper-casing with the default locale turns "file" into "FİLE",
 * which is how JetBrains Runtime's own Path.equals fails there (JBR-8664). Our host path rule must hold in that
 * locale.
 */
class HostPathsTurkishLocaleTest {
    private lateinit var saved: Locale

    @BeforeTest fun turkish() {
        saved = Locale.getDefault()
        Locale.setDefault(Locale.forLanguageTag("tr-TR"))
    }

    @AfterTest fun restore() = Locale.setDefault(saved)

    @Test fun theTrapIsReal() {
        // What a default-locale comparison does here: the reason ours must not use one.
        assertNotEquals("FILE", "file".uppercase(Locale.getDefault()))
    }

    @Test fun aWindowsHostPathComparesTheSameInTurkish() {
        assertTrue(sameHostPath("C:\\x\\FILE", "c:\\x\\file"))
        assertTrue(hostIsInside("C:\\Users\\me\\proj\\src\\Main.kt", "c:\\users\\ME\\PROJ"))
    }
}
