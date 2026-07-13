package dev.supermux.desktop.auth

import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    @Test fun clear_on_missing_file_is_true() {
        val s = tempStore()
        assertTrue(s.clear(), "clear() on a missing file should report success")
    }

    @Test fun clear_returns_false_when_delete_fails() {
        val s = tempStore(); s.save("t")
        val parent = s.path.parent
        val orig = runCatching { Files.getPosixFilePermissions(parent) }.getOrNull() ?: return
        try {
            Files.setPosixFilePermissions(
                parent,
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE),
            )
            assertFalse(s.clear(), "clear() should report failure when the file cannot be deleted")
        } finally {
            Files.setPosixFilePermissions(parent, orig)
        }
    }

    @Test fun temp_file_not_left_behind_after_save() {
        val s = tempStore()
        s.save("tok"); s.saveBaseUrl("ws://x:1")
        val names = Files.list(s.path.parent).use { stream ->
            stream.map { it.fileName.toString() }.sorted().toList()
        }
        assertEquals(listOf("auth.json"), names)
    }

    @Test fun default_path_is_under_config_dir() {
        val p = DesktopTokenStore.defaultPath()
        assertTrue(p.toString().contains("supermux"), "was $p")
        assertTrue(p.fileName.toString() == "auth.json")
    }
}
