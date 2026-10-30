package dev.supermux.webview.engine.linux

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream

/**
 * Unpacks a WPE runtime the app ships as one archive (`.tar.gz`, `.tar`, or `.tar.zst` with the system's `zstd`) into
 * `<home>/<version>-<hash>`: the runtime's version, and the first 16 hex digits of the archive's SHA-256. The engine
 * loads only a folder of that name, which exists only once the whole archive is in it, verified:
 * - it unpacks into a temporary folder of [home], then renames it, atomically;
 * - one process, and one thread, at a time: a lock in [home], so apps launched at once unpack it once;
 * - it refuses `..`, absolute paths, hard links, symlinks that leave the runtime, and files the manifest doesn't list;
 * - every file's SHA-256 is checked against the manifest, once, as it is written.
 *
 * The archive's one top folder (`wpe-runtime-<version>-linux-<arch>/`, as build-runtime.sh packs it) is the runtime's
 * folder. An index in [home] keyed by the archive's path, size and time spares hashing it at every launch.
 */
internal object WpeExtractor {
    private const val INDEX = ".archives"
    private const val LOCK = ".lock"
    private const val UNPACKING = ".unpacking-"

    /** One monitor per home in this process: a file lock is the process's, not a thread's. */
    private val monitors = ConcurrentHashMap<String, Any>()

    /** The verified, versioned folder of [archive] in [home], unpacked if it isn't yet; or why it can't be. */
    fun unpack(archive: File, home: File): Result<File> = runCatching {
        home.mkdirs()
        val key = home.absoluteFile.normalize().path
        synchronized(monitors.getOrPut(key) { Any() }) {
            FileChannel.open(File(home, LOCK).toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
                channel.lock().use { unpackLocked(archive, home) }
            }
        }
    }

    private fun unpackLocked(archive: File, home: File): File {
        if (!archive.isFile) throw IOException("no runtime archive at $archive")
        val stamp = "${archive.length()} ${archive.lastModified()} ${archive.absolutePath}"
        indexed(home, stamp)?.let { return it }
        val hash = sha256(archive.inputStream()).take(16)
        home.listFiles()?.firstOrNull { it.isDirectory && it.name.endsWith("-$hash") && !it.name.startsWith(".") }?.let {
            remember(home, stamp, it)
            return it
        }
        // What a crashed unpack left: never used, as only a rename makes a runtime's folder.
        home.listFiles()?.filter { it.name.startsWith(UNPACKING) }?.forEach { it.deleteRecursively() }
        val temp = Files.createTempDirectory(home.toPath(), UNPACKING).toFile()
        try {
            val written = open(archive).use { extract(it, temp) }
            val version = verify(temp, written)
            val target = File(home, "$version-$hash")
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            remember(home, stamp, target)
            return target
        } finally {
            if (temp.exists()) temp.deleteRecursively()
        }
    }

    /** The archive's tar stream. */
    private fun open(archive: File): InputStream {
        val name = archive.name
        return when {
            name.endsWith(".tar.gz") || name.endsWith(".tgz") -> GZIPInputStream(archive.inputStream().buffered(), 1 shl 16)
            name.endsWith(".tar") -> archive.inputStream().buffered(1 shl 16)
            name.endsWith(".tar.zst") -> {
                val process = try {
                    ProcessBuilder("zstd", "-dc", archive.absolutePath).redirectError(ProcessBuilder.Redirect.DISCARD).start()
                } catch (e: IOException) {
                    throw IOException("$archive needs zstd to unpack, which isn't installed: ship the runtime as .tar.gz instead")
                }
                process.inputStream.buffered(1 shl 16)
            }
            else -> throw IOException("$archive is no .tar.gz, .tar or .tar.zst")
        }
    }

    /** Writes the runtime in [input]'s tar into [root]; returns the regular files written, by path, with their SHA-256. */
    private fun extract(input: InputStream, root: File): Map<String, String> {
        val written = HashMap<String, String>()
        val rootPath = root.toPath().toAbsolutePath().normalize()
        var top: String? = null
        var longName: String? = null
        var longLink: String? = null
        var paxName: String? = null
        var paxLink: String? = null
        val header = ByteArray(512)
        while (true) {
            if (!readFully(input, header)) throw IOException("the archive ends without its end")
            if (header.all { it == 0.toByte() }) break
            val type = header[156].toInt().toChar()
            val size = octal(header, 124, 12)
            val name = paxName ?: longName ?: field(header, 0, 100).let { base ->
                val prefix = if (field(header, 257, 6) == "ustar") field(header, 345, 155) else ""
                if (prefix.isEmpty()) base else "$prefix/$base"
            }
            val link = paxLink ?: longLink ?: field(header, 157, 100)
            when (type) {
                'L', 'K' -> {
                    val text = readText(input, size)
                    if (type == 'L') longName = text else longLink = text
                    continue
                }
                'x' -> {
                    for ((k, v) in pax(readText(input, size))) {
                        if (k == "path") paxName = v
                        if (k == "linkpath") paxLink = v
                    }
                    continue
                }
                'g' -> {
                    skip(input, size)
                    continue
                }
            }
            longName = null
            longLink = null
            paxName = null
            paxLink = null
            if (name.startsWith("/")) throw IOException("an absolute path in the archive: $name")
            val parts = name.removePrefix("./").split('/').filter { it.isNotEmpty() && it != "." }
            if (".." in parts) throw IOException("a .. in the archive: $name")
            if (parts.isEmpty()) {
                skip(input, size)
                continue
            }
            if (top == null) top = parts[0]
            if (parts[0] != top) throw IOException("more than the runtime's folder in the archive: $name")
            val relative = parts.drop(1).joinToString("/")
            if (relative.isEmpty()) {
                skip(input, size)
                continue
            }
            val target = File(root, relative)
            when (type) {
                '5' -> {
                    target.mkdirs()
                    skip(input, size)
                }
                '0', '\u0000', '7' -> {
                    target.parentFile.mkdirs()
                    val digest = MessageDigest.getInstance("SHA-256")
                    target.outputStream().use { out -> copy(DigestInputStream(LimitedStream(input, size), digest), out) }
                    skipPadding(input, size)
                    val mode = octal(header, 100, 8)
                    if (mode and 0b001_001_001L != 0L) target.setExecutable(true, false)
                    written[relative] = digest.digest().joinToString("") { "%02x".format(it) }
                }
                '2' -> {
                    if (link.startsWith("/")) throw IOException("a symlink outside the runtime: $name -> $link")
                    val resolved = target.toPath().toAbsolutePath().parent.resolve(link).normalize()
                    if (!resolved.startsWith(rootPath)) throw IOException("a symlink outside the runtime: $name -> $link")
                    target.parentFile.mkdirs()
                    Files.createSymbolicLink(target.toPath(), Paths.get(link))
                    skip(input, size)
                }
                '1' -> throw IOException("a hard link in the archive: $name")
                else -> throw IOException("an entry of type '$type' in the archive: $name")
            }
        }
        return written
    }

    /** Checks [root] against its manifest: every file it lists, at its size and SHA-256, and nothing more. Its version. */
    private fun verify(root: File, written: Map<String, String>): String {
        val manifestFile = File(root, "manifest.properties")
        if (!manifestFile.isFile) throw IOException("the archive has no manifest.properties")
        val manifest = Properties().apply { manifestFile.inputStream().use { load(it) } }
        val listed = manifest.stringPropertyNames().filter { it.startsWith("file.") }.associate { key ->
            key.removePrefix("file.") to manifest.getProperty(key).trim().split(' ')
        }
        for ((path, entry) in listed) {
            val size = entry.getOrNull(0)?.toLongOrNull() ?: throw IOException("the manifest's entry for $path has no size")
            val sha = written[path] ?: throw IOException("$path, in the manifest, isn't in the archive")
            if (File(root, path).length() != size || sha != entry.getOrNull(1)) throw IOException("$path doesn't match its sha256 and size in the manifest")
        }
        val unlisted = written.keys - listed.keys - "manifest.properties"
        if (unlisted.isNotEmpty()) throw IOException("files the manifest doesn't list: ${unlisted.take(5)}")
        val version = manifest.getProperty("version").orEmpty()
        if (!version.matches(Regex("[0-9A-Za-z._-]{1,32}"))) throw IOException("the manifest's version isn't one: '$version'")
        return version
    }

    private fun indexed(home: File, stamp: String): File? {
        val index = File(home, INDEX).takeIf { it.isFile } ?: return null
        val name = index.readLines().lastOrNull { it.substringBefore('\t') == stamp }?.substringAfter('\t') ?: return null
        return File(home, name).takeIf { it.isDirectory && !name.startsWith(".") && '/' !in name }
    }

    private fun remember(home: File, stamp: String, dir: File) {
        File(home, INDEX).appendText("$stamp\t${dir.name}\n")
    }

    private fun sha256(input: InputStream): String = input.use { stream ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(1 shl 16)
        while (true) {
            val n = stream.read(buffer)
            if (n < 0) break
            digest.update(buffer, 0, n)
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun field(header: ByteArray, offset: Int, length: Int): String {
        var end = offset
        while (end < offset + length && header[end] != 0.toByte()) end++
        return String(header, offset, end - offset, Charsets.UTF_8).trim()
    }

    private fun octal(header: ByteArray, offset: Int, length: Int): Long {
        val text = field(header, offset, length).trim()
        return if (text.isEmpty()) 0 else text.toLong(8)
    }

    private fun pax(text: String): List<Pair<String, String>> = text.lines().mapNotNull { line ->
        val record = line.substringAfter(' ', "")
        if ('=' !in record) null else record.substringBefore('=') to record.substringAfter('=')
    }

    private fun readText(input: InputStream, size: Long): String {
        val bytes = ByteArray(size.toInt())
        if (!readFully(input, bytes)) throw IOException("the archive ends in a header")
        skipPadding(input, size)
        return String(bytes, Charsets.UTF_8).trimEnd('\u0000')
    }

    private fun readFully(input: InputStream, buffer: ByteArray): Boolean {
        var read = 0
        while (read < buffer.size) {
            val n = input.read(buffer, read, buffer.size - read)
            if (n < 0) return false
            read += n
        }
        return true
    }

    private fun skip(input: InputStream, size: Long) {
        copy(LimitedStream(input, size), null)
        skipPadding(input, size)
    }

    private fun skipPadding(input: InputStream, size: Long) {
        val padding = ((512 - size % 512) % 512).toInt()
        if (padding > 0 && !readFully(input, ByteArray(padding))) throw IOException("the archive ends in an entry")
    }

    private fun copy(from: InputStream, to: java.io.OutputStream?) {
        val buffer = ByteArray(1 shl 16)
        while (true) {
            val n = from.read(buffer)
            if (n < 0) break
            to?.write(buffer, 0, n)
        }
    }

    /** [input]'s next [size] bytes; an error if it ends before. */
    private class LimitedStream(private val input: InputStream, private var left: Long) : InputStream() {
        override fun read(): Int {
            if (left <= 0) return -1
            val b = input.read()
            if (b < 0) throw IOException("the archive ends in an entry")
            left--
            return b
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (left <= 0) return -1
            val n = input.read(buffer, offset, minOf(length.toLong(), left).toInt())
            if (n < 0) throw IOException("the archive ends in an entry")
            left -= n
            return n
        }
    }
}
