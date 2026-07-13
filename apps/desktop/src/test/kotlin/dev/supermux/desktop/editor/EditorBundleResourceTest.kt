package dev.supermux.desktop.editor

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The `processResources` Copy in build.gradle.kts must land the committed CodeMirror bundle on the
 * classpath under editor/. If this fails, the editor can never load a page — so it's a build gate.
 */
class EditorBundleResourceTest {

    @Test
    fun cm6_bundle_is_on_the_classpath_and_over_1mb() {
        val bytes = javaClass.classLoader.getResourceAsStream("editor/cm6.js").use { it?.readBytes() }
        assertNotNull(bytes, "editor/cm6.js missing from classpath — processResources copy did not run")
        assertTrue(bytes.size > 1_000_000, "editor/cm6.js is ${bytes.size} bytes, expected >1MB")
    }

    @Test
    fun index_html_is_on_the_classpath() {
        val html = javaClass.classLoader.getResourceAsStream("editor/index.html").use {
            it?.readBytes()?.decodeToString()
        }
        assertNotNull(html, "editor/index.html missing from classpath")
        assertTrue(html.contains("cm6.js"), "index.html should reference the cm6.js bundle")
        assertTrue(html.contains("#282c34"), "index.html should carry the editor's dark background")
    }
}
