package dev.supermux.desktop.auth

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopTokenStoreTest {
    private fun tempStore(): DesktopTokenStore =
        DesktopTokenStore(Files.createTempDirectory("smx-store").resolve("auth.json"))

    @Test fun starts_empty() {
        val s = tempStore()
        assertNull(s.load()); assertNull(s.loadBaseUrl())
    }

    @Test fun saves_and_reloads_across_instances() {
        val s = tempStore()
        s.saveBaseUrl("ws://127.0.0.1:9898"); s.save("tok123")
        val again = DesktopTokenStore(s.path)
        assertEquals("tok123", again.load())
        assertEquals("ws://127.0.0.1:9898", again.loadBaseUrl())
    }

    @Test fun clear_removes_both() {
        val s = tempStore()
        s.saveBaseUrl("ws://x:1"); s.save("t"); s.clear()
        val again = DesktopTokenStore(s.path)
        assertNull(again.load()); assertNull(again.loadBaseUrl())
    }

    @Test fun file_is_owner_only_on_posix() {
        val s = tempStore(); s.save("secret")
        val posix = runCatching { Files.getPosixFilePermissions(s.path) }.getOrNull() ?: return
        assertTrue(posix.all { it.name.startsWith("OWNER_") }, "perms were $posix")
    }

    @Test fun corrupt_file_reads_as_empty() {
        val s = tempStore()
        Files.createDirectories(s.path.parent); Files.writeString(s.path, "{not json")
        assertNull(s.load())
    }

    @Test fun default_path_is_under_config_dir() {
        val p = DesktopTokenStore.defaultPath()
        assertTrue(p.toString().contains("supermux"), "was $p")
        assertTrue(p.fileName.toString() == "auth.json")
    }
}
