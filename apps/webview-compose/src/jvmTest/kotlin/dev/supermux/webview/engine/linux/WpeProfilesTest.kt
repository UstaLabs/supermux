package dev.supermux.webview.engine.linux

import dev.supermux.webview.WebProfile
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WpeProfilesTest {
    @Test fun theAppIsWhatItSaysElseItsLauncherElseItsMainClassAndWhereItLives() {
        assertEquals("com.example.chat", WpeProfiles.appId("com.example.chat", "/opt/chat/bin/Chat", "x.MainKt", "/a"))
        assertEquals("Chat", WpeProfiles.appId(null, "/opt/chat/bin/Chat", "x.MainKt", "/a"), "a packaged app: its launcher")
        val dev = WpeProfiles.appId(null, null, "dev.supermux.webview.sample.MainKt --flag", "/home/u/app/build/classes:/lib/x.jar")
        assertTrue(dev.startsWith("dev.supermux.webview.sample.MainKt-"), dev)
        assertNotEquals(dev, WpeProfiles.appId(null, null, "dev.supermux.webview.sample.MainKt", "/home/u/other/build/classes"), "another checkout")
        assertTrue(WpeProfiles.appId(null, null, "/opt/tool/tool.jar", "/opt/tool/tool.jar").startsWith("tool-"))
        assertEquals("a_b_c", WpeProfiles.appId("a/b c", null, null, null), "a folder's name, never a path")
        assertEquals("default", WpeProfiles.appId(null, null, null, null))
    }

    @Test fun eachProfileHasAFolderInItsAppsOwn() {
        val root = File("/data/dev.supermux.webview/wpe/chat")
        assertEquals(File(root, "default"), WpeProfiles.dataDir(root, WebProfile.Default))
        assertEquals(File(root, "profiles/work"), WpeProfiles.dataDir(root, WebProfile.Named("work")))
        assertNull(WpeProfiles.dataDir(root, WebProfile.Ephemeral))
    }

    @Test fun aProfileFolderAnotherHolderHasIsRefused() {
        val dir = Files.createTempDirectory("profile").toFile()
        val locks = WpeProfiles.Locks()
        assertNull(locks.lock(dir))
        assertNull(locks.lock(dir), "this process's already")
        val other = Files.createTempDirectory("profile").toFile()
        FileChannel.open(File(other, WpeProfiles.LOCK_FILE).toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
            channel.lock().use {
                assertTrue(locks.lock(other)!!.contains("in use"))
            }
        }
    }
}
