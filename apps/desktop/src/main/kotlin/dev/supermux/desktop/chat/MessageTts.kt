package dev.supermux.desktop.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * Desktop native "Read aloud" via the host OS speech CLI:
 * - macOS: `say`
 * - Linux: `spd-say` (speech-dispatcher), else `espeak-ng` / `espeak`
 * - Windows: PowerShell System.Speech
 *
 * Only one utterance at a time; [speakingKey] is Compose-observable.
 */
object MessageTts {
    private val gen = AtomicInteger(0)
    private val process = AtomicReference<Process?>(null)

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
        speak(plain)
    }

    fun stop() {
        gen.incrementAndGet()
        process.getAndSet(null)?.destroyForcibly()
        speakingKey = null
    }

    private fun speak(plain: String) {
        stop()
        val g = gen.incrementAndGet()
        speakingKey = plain
        val cmd = speechCommand(plain) ?: run {
            speakingKey = null
            return
        }
        thread(name = "message-tts", isDaemon = true) {
            try {
                val p = ProcessBuilder(cmd)
                    .redirectErrorStream(true)
                    .start()
                process.set(p)
                p.waitFor()
            } catch (_: Exception) {
                // CLI missing or killed
            } finally {
                if (gen.get() == g) {
                    process.compareAndSet(process.get(), null)
                    // Compose state must flip on the EDT-ish main thread when possible;
                    // desktop Compose accepts updates from any thread for mutableStateOf.
                    speakingKey = null
                }
            }
        }
    }

    /** Best-effort OS command; null if nothing available. */
    internal fun speechCommand(plain: String): List<String>? {
        val os = System.getProperty("os.name").orEmpty().lowercase()
        return when {
            os.contains("mac") || os.contains("darwin") -> listOf("say", plain)
            os.contains("win") -> {
                // Escape single quotes for PowerShell single-quoted string.
                val escaped = plain.replace("'", "''")
                listOf(
                    "powershell",
                    "-NoProfile",
                    "-Command",
                    "Add-Type -AssemblyName System.Speech; " +
                        "(New-Object System.Speech.Synthesis.SpeechSynthesizer).Speak('$escaped')",
                )
            }
            // Linux / other Unix
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

/** Flatten markdown-ish agent text for TTS (mirrors web/Android). */
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
