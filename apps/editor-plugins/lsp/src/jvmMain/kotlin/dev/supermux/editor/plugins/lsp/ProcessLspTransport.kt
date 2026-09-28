package dev.supermux.editor.plugins.lsp

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.OutputStream

/**
 * A language server process over stdio (the desktop: `clangd`, `rust-analyzer`, …), LSP's
 * `Content-Length` framing on both pipes. Messages are read on an IO thread; [close] (or the
 * process exiting) makes [status] [LspConnState.DISCONNECTED].
 */
class ProcessLspTransport(command: List<String>, workDir: File? = null, scope: CoroutineScope) : LspTransport {
    private val process: Process = ProcessBuilder(command).apply { if (workDir != null) directory(workDir) }.redirectError(ProcessBuilder.Redirect.DISCARD).start()
    private val input = BufferedInputStream(process.inputStream)
    private val output: OutputStream = process.outputStream
    private val messages = Channel<String>(Channel.UNLIMITED)
    private val statusFlow = MutableStateFlow(LspConnState.CONNECTED)

    override val incoming: Flow<String> = messages.receiveAsFlow()
    override val status: StateFlow<LspConnState> = statusFlow

    init {
        scope.launch(Dispatchers.IO) {
            try {
                while (true) messages.send(readMessage() ?: break)
            } catch (_: Exception) {
            } finally {
                statusFlow.value = LspConnState.DISCONNECTED
            }
        }
    }

    private fun readMessage(): String? {
        var length = -1
        while (true) {
            val line = readHeaderLine() ?: return null
            if (line.isEmpty()) break
            val i = line.indexOf(':')
            if (i > 0 && line.substring(0, i).trim().equals("Content-Length", ignoreCase = true)) length = line.substring(i + 1).trim().toInt()
        }
        if (length < 0) return null
        val buf = ByteArray(length)
        var n = 0
        while (n < length) { val r = input.read(buf, n, length - n); if (r < 0) return null; n += r }
        return buf.decodeToString()
    }

    private fun readHeaderLine(): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return null
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
        }
    }

    override suspend fun send(message: String) {
        val body = message.encodeToByteArray()
        withContext(Dispatchers.IO) {
            synchronized(output) {
                output.write("Content-Length: ${body.size}\r\n\r\n".encodeToByteArray())
                output.write(body)
                output.flush()
            }
        }
    }

    fun close() {
        runCatching { output.close() }
        process.destroy()
        statusFlow.value = LspConnState.DISCONNECTED
    }

    val alive: Boolean get() = process.isAlive

    companion object {
        /** The first of [names] found on the PATH (and a few usual places), or null. */
        fun find(vararg names: String): String? {
            val dirs = (System.getenv("PATH").orEmpty().split(File.pathSeparator) + listOf("/usr/bin", "/usr/local/bin", "/opt/homebrew/bin", System.getProperty("user.home") + "/.cargo/bin", System.getProperty("user.home") + "/.mux/lsp/bin"))
            for (n in names) for (d in dirs) { val f = File(d, n); if (f.canExecute()) return f.absolutePath }
            return null
        }
    }
}
