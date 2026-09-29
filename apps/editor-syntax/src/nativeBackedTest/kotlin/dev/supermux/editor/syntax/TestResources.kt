package dev.supermux.editor.syntax

/** A file staged by :editor-syntax:stageTestTables (e.g. "sesz/fsharp.sesz") or under src/nativeBackedTest/resources. */
expect fun testResource(path: String): ByteArray

/** Point the platform's resource lookup at the staged app resources (iOS tests have no app bundle). */
internal expect fun useTestAppResources()

/**
 * A [NativeBackend] reading the app's tables resources, as an app would, with every grammar
 * loaded (the tests of a Highlighter need its languages ready; the worker's own loading of
 * languages that are not is tested with a backend of its own).
 */
internal fun testBackend(): NativeBackend {
    useTestAppResources()
    return NativeBackend().also { b -> b.languages.forEach { b.ensureLanguageNow(it) } }
}
