package dev.supermux.editor.syntax

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Properties

/**
 * Finds and loads `supermux_syntax_jni`, in this order:
 * 1. `-Deditor.syntax.lib=<absolute path>` (JVM tests; no checks).
 * 2. The desktop library packaged in the jar for this os/arch, staged by
 *    `:editor-syntax:stageJvmNativeResources` as
 *    `/dev/supermux/editor/syntax/natives/<os>-<arch>/{<library>, native.properties}`: its sha256
 *    and size are checked, then it is extracted to a fresh private temp directory and loaded.
 * 3. `System.loadLibrary`: Android, where the library comes from the APK's jniLibs.
 */
internal object SesNativeLoader {
    const val LIBRARY_PROPERTY = "editor.syntax.lib"
    const val RESOURCE_ROOT = "/dev/supermux/editor/syntax/natives"

    fun load() {
        System.getProperty(LIBRARY_PROPERTY)?.takeIf { it.isNotBlank() }?.let { System.load(it); return }
        val key = platformKey()
        val props = key?.let { SesNativeLoader::class.java.getResourceAsStream("$RESOURCE_ROOT/$it/native.properties") }
        if (props == null) { System.loadLibrary("supermux_syntax_jni"); return }
        val p = Properties().apply { props.use { load(it) } }
        val library = p.getProperty("library")?.takeIf { it.isNotEmpty() && '/' !in it && '\\' !in it }
            ?: throw UnsatisfiedLinkError("$RESOURCE_ROOT/$key/native.properties: no library")
        val bytes = SesNativeLoader::class.java.getResourceAsStream("$RESOURCE_ROOT/$key/$library")?.use { it.readBytes() }
            ?: throw UnsatisfiedLinkError("$RESOURCE_ROOT/$key/$library is not packaged")
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        if (sha != p.getProperty("sha256") || bytes.size.toString() != p.getProperty("size")) {
            throw UnsatisfiedLinkError("packaged $library does not match its native.properties (sha256 $sha)")
        }
        // A fresh owner-only directory per process: nothing another user pre-created is ever loaded.
        val dir = Files.createTempDirectory("supermux-syntax-").toFile()
        val file = File(dir, library)
        file.writeBytes(bytes)
        dir.deleteOnExit()
        file.deleteOnExit()
        System.load(file.absolutePath)
    }

    /** `<os>-<arch>` as native/build.sh names the desktop targets, or null for anything else. */
    fun platformKey(): String? {
        val os = System.getProperty("os.name").orEmpty().lowercase()
        val o = when {
            os.startsWith("linux") -> "linux"
            os.startsWith("mac") || os.startsWith("darwin") -> "macos"
            os.startsWith("windows") -> "windows"
            else -> return null
        }
        val a = when (System.getProperty("os.arch").orEmpty().lowercase()) {
            "amd64", "x86_64", "x64" -> "x64"
            "aarch64", "arm64" -> "arm64"
            else -> return null
        }
        return "$o-$a"
    }
}
