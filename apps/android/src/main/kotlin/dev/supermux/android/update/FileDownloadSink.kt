package dev.supermux.android.update

import dev.supermux.update.DownloadSink
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile

/**
 * An installer download written straight to [file] (#140): the APK never sits in the heap, where
 * 51 MB held twice over was enough for a low-RAM phone to kill the app at 100% and leave it on the
 * old version. Starts empty; [close] when the download ends.
 */
internal class FileDownloadSink(file: File) : DownloadSink, Closeable {
    private val raf = RandomAccessFile(file, "rw").apply { setLength(0) }

    override val size: Long get() = raf.length()

    override fun write(bytes: ByteArray, offset: Int, length: Int) = raf.write(bytes, offset, length)

    override fun clear() {
        raf.setLength(0)
        raf.seek(0)
    }

    override fun close() = raf.close()
}
