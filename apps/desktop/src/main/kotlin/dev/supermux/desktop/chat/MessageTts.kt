package dev.supermux.desktop.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Desktop read-aloud: OS CLI TTS (platform) or ChatGPT via broker /speak (codex).
 * Wire [resolveEngine] / [speakRemote] from the host connection layer when available.
 */
object MessageTts {
    private val gen = AtomicInteger(0)
    private val process = AtomicReference<Process?>(null)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    var resolveEngine: (suspend () -> String)? = null
    var speakRemote: (suspend (text: String) -> ByteArray)? = null

    var speakingKey by mutableStateOf<String?>(null)
        private set

    fun isSpeaking(textKey: String): Boolean = speakingKey == textKey

    fun toggle(rawText: String) {
        val plain = plainTextForSpeech(rawText)
        if (plain.isBlank()) return
        if (speakingKey == plain) {
            stop()
            return
        }
        scope.launch {
            val eng = runCatching { resolveEngine?.invoke() }.getOrNull()?.ifBlank { null } ?: "platform"
            if (eng == "codex" && speakRemote != null) {
                speakCodex(plain, rawText)
            } else {
                speakPlatform(plain)
            }
        }
    }

    fun stop() {
        gen.incrementAndGet()
        process.getAndSet(null)?.destroyForcibly()
        speakingKey = null
    }

    private fun speakCodex(plain: String, rawText: String) {
        val remote = speakRemote ?: return speakPlatform(plain)
        stop()
        val g = gen.incrementAndGet()
        speakingKey = plain
        thread(name = "message-tts-codex", isDaemon = true) {
            try {
                val bytes = runBlocking { remote(rawText) }
                if (gen.get() != g) return@thread
                val file = File.createTempFile("read-aloud-", ".mp3")
                file.deleteOnExit()
                file.writeBytes(bytes)
                val playCmd = playCommand(file.absolutePath)
                if (playCmd == null) {
                    if (gen.get() == g) speakingKey = null
                    return@thread
                }
                val p = ProcessBuilder(playCmd).redirectErrorStream(true).start()
                process.set(p)
                p.waitFor()
            } catch (_: Exception) {
                // fall through
            } finally {
                if (gen.get() == g) {
                    process.compareAndSet(process.get(), null)
                    speakingKey = null
                }
            }
        }
    }

    private fun speakPlatform(plain: String) {
        stop()
        val g = gen.incrementAndGet()
        speakingKey = plain
        val cmd = speechCommand(plain) ?: run {
            speakingKey = null
            return
        }
        thread(name = "message-tts", isDaemon = true) {
            try {
                val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
                process.set(p)
                p.waitFor()
            } catch (_: Exception) {
            } finally {
                if (gen.get() == g) {
                    process.compareAndSet(process.get(), null)
                    speakingKey = null
                }
            }
        }
    }

    private fun playCommand(path: String): List<String>? = when {
        which("ffplay") -> listOf("ffplay", "-nodisp", "-autoexit", "-loglevel", "quiet", path)
        which("mpv") -> listOf("mpv", "--no-video", "--really-quiet", path)
        which("afplay") -> listOf("afplay", path) // macOS
        else -> null
    }

    internal fun speechCommand(plain: String): List<String>? {
        val os = System.getProperty("os.name").orEmpty().lowercase()
        return when {
            os.contains("mac") || os.contains("darwin") -> listOf("say", plain)
            os.contains("win") -> {
                val escaped = plain.replace("'", "''")
                listOf(
                    "powershell",
                    "-NoProfile",
                    "-Command",
                    "Add-Type -AssemblyName System.Speech; " +
                        "(New-Object System.Speech.Synthesis.SpeechSynthesizer).Speak('$escaped')",
                )
            }
            which("spd-say") -> listOf("spd-say", "-e", plain)
            which("espeak-ng") -> listOf("espeak-ng", plain)
            which("espeak") -> listOf("espeak", plain)
            else -> null
        }
    }

    private fun which(bin: String): Boolean {
        return try {
            ProcessBuilder("which", bin).start().waitFor() == 0
        } catch (_: Exception) {
            false
        }
    }
}

fun plainTextForSpeech(md: String): String {
    if (md.isBlank()) return ""
    var s = md
    s = s.replace(Regex("```[\\s\\S]*?```"), " ")
    s = s.replace(Regex("`([^`]+)`"), "$1")
    s = s.replace(Regex("!\\[([^\\]]*)\\]\\([^)]*\\)"), "$1")
    s = s.replace(Regex("\\[([^\\]]+)\\]\\([^)]*\\)"), "$1")
    s = s.replace(Regex("^#{1,6}\\s+", RegexOption.MULTILINE), "")
    s = s.replace(Regex("^\\s*[-*+]\\s+", RegexOption.MULTILINE), "")
    s = s.replace(Regex("^\\s*\\d+\\.\\s+", RegexOption.MULTILINE), "")
    s = s.replace(Regex("^\\s*>\\s?", RegexOption.MULTILINE), "")
    s = s.replace(Regex("(\\*\\*|__)(.*?)\\1"), "$2")
    s = s.replace(Regex("(\\*|_)(.*?)\\1"), "$2")
    s = s.replace(Regex("~~(.*?)~~"), "$1")
    s = s.replace(Regex("\\n{2,}"), ". ")
    s = s.replace('\n', ' ')
    s = s.replace(Regex("\\s+"), " ").trim()
    s = s.replace(Regex("(?:\\.\\s*){2,}"), ". ").replace(Regex("\\s+"), " ").trim()
    return s
}
