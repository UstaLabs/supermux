package dev.supermux.editor.syntax

/**
 * JNI actual (native/src/syntax_jni.c, lib `supermux_syntax_jni`), shared by Android and the desktop
 * JVM. Strings cross as java.lang.String, i.e. UTF-16 already: the C side passes GetStringChars
 * straight to tree-sitter as UTF-16LE.
 *
 * Loading: `-Deditor.syntax.lib=<absolute path>` (JVM tests), the jar's packaged desktop library,
 * or System.loadLibrary (Android): see [SesNativeLoader].
 */
internal actual object Ses {
    init {
        SesNativeLoader.load()
    }

    /** What the C reader calls back: `String chunk(int)`. One String per chunk, no copies on our side. */
    private class TextSourceJni(private val source: TextSource) {
        @Suppress("unused") // called from JNI
        fun chunk(index: Int): String = source.chunkAt(index).toString()
    }

    @JvmStatic actual external fun abiVersion(): Int
    @JvmStatic actual external fun languageCount(): Int
    @JvmStatic actual external fun languageName(index: Int): String
    @JvmStatic actual external fun languageHasTables(name: String): Int
    @JvmStatic actual external fun provideTables(name: String, bytes: ByteArray): Int
    @JvmStatic actual external fun languageLoad(name: String): Int
    @JvmStatic actual external fun parserNew(): Long
    @JvmStatic actual external fun parserFree(parser: Long)
    @JvmStatic actual external fun parserSetLanguage(parser: Long, name: String): Int
    @JvmStatic actual external fun parserSetTimeoutMicros(parser: Long, micros: Long)

    @JvmStatic private external fun parse(parser: Long, old: Long, source: Any, status: IntArray): Long
    actual fun parse(parser: Long, old: Long, source: TextSource, status: IntArray): Long =
        parse(parser, old, TextSourceJni(source) as Any, status)

    @JvmStatic actual external fun parseString(parser: Long, old: Long, text: String, status: IntArray): Long
    @JvmStatic actual external fun treeCopy(tree: Long): Long
    @JvmStatic actual external fun treeFree(tree: Long)
    @JvmStatic actual external fun treeEdit(
        tree: Long, start: Int, oldEnd: Int, newEnd: Int, sr: Int, sc: Int, oer: Int, oec: Int, ner: Int, nec: Int,
    )
    @JvmStatic actual external fun treeSexp(tree: Long): String
    @JvmStatic actual external fun treeHasError(tree: Long): Boolean
    @JvmStatic actual external fun treeChangedRanges(old: Long, new: Long): IntArray
    @JvmStatic actual external fun queryNew(language: String, utf8: ByteArray, err: IntArray): Long
    @JvmStatic actual external fun queryFree(query: Long)
    @JvmStatic actual external fun queryCaptureCount(query: Long): Int
    @JvmStatic actual external fun queryCaptureName(query: Long, index: Int): ByteArray
    @JvmStatic actual external fun queryFlags(query: Long): Int

    @JvmStatic private external fun queryCaptures(query: Long, tree: Long, start: Int, end: Int, source: Any?): IntArray
    actual fun queryCaptures(query: Long, tree: Long, start: Int, end: Int, source: TextSource?): IntArray =
        queryCaptures(query, tree, start, end, source?.let { TextSourceJni(it) } as Any?)
}
