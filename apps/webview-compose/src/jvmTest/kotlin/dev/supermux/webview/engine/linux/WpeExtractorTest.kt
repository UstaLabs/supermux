package dev.supermux.webview.engine.linux

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WpeExtractorTest {
    /** One tar entry: a file ('0'), a folder ('5'), a symlink ('2') or a hard link ('1'). */
    private class Entry(val name: String, val type: Char = '0', val data: ByteArray = ByteArray(0), val link: String = "", val mode: Int = 420)

    /** A GNU tar of [entries], with a long-name entry ('L') for a name over 100 bytes. */
    private fun tar(entries: List<Entry>): ByteArray {
        val out = ByteArrayOutputStream()
        fun header(name: String, type: Char, size: Int, link: String, mode: Int) {
            val h = ByteArray(512)
            fun put(offset: Int, length: Int, text: String) = text.encodeToByteArray().copyInto(h, offset, 0, minOf(length, text.length))
            fun octal(offset: Int, length: Int, value: Long) = put(offset, length - 1, value.toString(8).padStart(length - 1, '0'))
            put(0, 100, name)
            octal(100, 8, mode.toLong())
            octal(108, 8, 0)
            octal(116, 8, 0)
            octal(124, 12, size.toLong())
            octal(136, 12, 0)
            "        ".encodeToByteArray().copyInto(h, 148)
            h[156] = type.code.toByte()
            put(157, 100, link)
            put(257, 8, "ustar  ")
            val sum = h.sumOf { it.toInt() and 0xff }
            put(148, 7, sum.toString(8).padStart(6, '0') + "\u0000")
            out.write(h)
        }
        fun body(data: ByteArray) {
            out.write(data)
            out.write(ByteArray((512 - data.size % 512) % 512))
        }
        for (e in entries) {
            if (e.name.length > 100) {
                val long = e.name.encodeToByteArray() + 0
                header("././@LongLink", 'L', long.size, "", 420)
                body(long)
            }
            header(e.name.take(100), e.type, e.data.size, e.link, e.mode)
            body(e.data)
        }
        out.write(ByteArray(1024))
        return out.toByteArray()
    }

    private fun sha(data: ByteArray) = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

    /** A runtime's entries under [root]: its manifest lists [files] (or what [listed] says), plus [extra] entries. */
    private fun runtime(
        root: String = "wpe-runtime-2.54.2-linux-x64",
        files: Map<String, ByteArray> = mapOf("lib/libwebview-wpe.so" to byteArrayOf(1, 2, 3), "libexec/WPEWebProcess" to byteArrayOf(4)),
        listed: Map<String, String>? = null,
        extra: List<Entry> = emptyList(),
    ): List<Entry> {
        val manifest = buildString {
            append("runtime=wpe\nversion=2.54.2\nabi=${WpeRuntime.ABI}\narch=x64\n")
            for ((path, data) in files) append("file.$path=${data.size} ${listed?.get(path) ?: sha(data)}\n")
        }
        return listOf(Entry("$root/", '5'), Entry("$root/lib/", '5'), Entry("$root/libexec/", '5')) +
            files.map { (path, data) -> Entry("$root/$path", data = data, mode = if (path.startsWith("libexec/")) 493 else 420) } +
            Entry("$root/manifest.properties", data = manifest.encodeToByteArray()) + extra
    }

    private fun archive(entries: List<Entry>, gzip: Boolean = false): File {
        val bytes = tar(entries)
        val file = Files.createTempFile("runtime", if (gzip) ".tar.gz" else ".tar").toFile()
        if (gzip) GZIPOutputStream(file.outputStream()).use { it.write(bytes) } else file.writeBytes(bytes)
        return file
    }

    private fun home() = Files.createTempDirectory("extracted").toFile()

    @Test fun anArchiveUnpacksOnceIntoAFolderNamedForItsVersionAndHash() {
        val archive = archive(runtime(), gzip = true)
        val home = home()
        val dir = WpeExtractor.unpack(archive, home).getOrThrow()
        assertEquals(home, dir.parentFile)
        assertTrue(dir.name.startsWith("2.54.2-") && dir.name.length == "2.54.2-".length + 16, dir.name)
        assertTrue(File(dir, "libexec/WPEWebProcess").canExecute())
        assertEquals(listOf<Byte>(1, 2, 3), File(dir, "lib/libwebview-wpe.so").readBytes().toList())
        val again = WpeExtractor.unpack(archive, home).getOrThrow()
        assertEquals(dir, again)
        assertEquals(listOf(dir.name), home.list()!!.filter { !it.startsWith(".") }, "nothing else, nothing left over")
    }

    @Test fun aFileThatIsntTheManifestsIsRefused() {
        val tampered = archive(runtime(listed = mapOf("lib/libwebview-wpe.so" to "0".repeat(64))))
        assertRefused(tampered, "sha256")
        assertRefused(archive(runtime(extra = listOf(Entry("wpe-runtime-2.54.2-linux-x64/lib/smuggled.so", data = byteArrayOf(9))))), "manifest")
    }

    @Test fun entriesThatLeaveTheFolderAreRefused() {
        assertRefused(archive(runtime(extra = listOf(Entry("wpe-runtime-2.54.2-linux-x64/../evil", data = byteArrayOf(1))))), "..")
        assertRefused(archive(runtime(extra = listOf(Entry("/tmp/evil", data = byteArrayOf(1))))), "absolute")
        assertRefused(archive(runtime(extra = listOf(Entry("wpe-runtime-2.54.2-linux-x64/lib/out", '2', link = "../../../etc/passwd")))), "outside")
        assertRefused(archive(runtime(extra = listOf(Entry("wpe-runtime-2.54.2-linux-x64/lib/abs", '2', link = "/etc/passwd")))), "outside")
        assertRefused(archive(runtime(extra = listOf(Entry("wpe-runtime-2.54.2-linux-x64/lib/hard", '1', link = "wpe-runtime-2.54.2-linux-x64/lib/libwebview-wpe.so")))), "link")
    }

    @Test fun aSymlinkInsideAndALongNameAreKept() {
        val long = "share/" + "x".repeat(120) + ".dat"
        val files = mapOf("lib/libwebview-wpe.so" to byteArrayOf(1, 2, 3), "libexec/WPEWebProcess" to byteArrayOf(4), long to byteArrayOf(7))
        val dir = WpeExtractor.unpack(archive(runtime(files = files, extra = listOf(Entry("wpe-runtime-2.54.2-linux-x64/lib/libwebview.so", '2', link = "libwebview-wpe.so")))), home()).getOrThrow()
        assertEquals(listOf<Byte>(1, 2, 3), File(dir, "lib/libwebview.so").readBytes().toList())
        assertEquals(listOf<Byte>(7), File(dir, long).readBytes().toList())
    }

    @Test fun launchesAtOnceUnpackItOnce() {
        val archive = archive(runtime(), gzip = true)
        val home = home()
        val pool = Executors.newFixedThreadPool(4)
        val dirs = (1..4).map { pool.submit<File> { WpeExtractor.unpack(archive, home).getOrThrow() } }.map { it.get() }
        pool.shutdown()
        assertEquals(1, dirs.toSet().size)
        assertEquals(1, home.list()!!.count { !it.startsWith(".") })
    }

    @Test fun whatACrashedUnpackLeftIsntUsed() {
        val archive = archive(runtime())
        val home = home()
        File(home, ".unpacking-1234").apply { mkdirs(); File(this, "half").writeText("x") }
        val dir = WpeExtractor.unpack(archive, home).getOrThrow()
        assertTrue(File(dir, "manifest.properties").isFile)
        assertFalse(File(home, ".unpacking-1234").exists(), "cleared")
    }

    private fun assertRefused(archive: File, why: String) {
        val home = home()
        val result = WpeExtractor.unpack(archive, home)
        assertTrue(result.isFailure, "unpacked: ${result.getOrNull()}")
        assertTrue(result.exceptionOrNull()!!.message!!.contains(why), result.exceptionOrNull()!!.message)
        assertEquals(emptyList(), home.list()!!.filter { !it.startsWith(".") }, "no folder")
    }
}
