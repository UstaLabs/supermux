package dev.supermux.desktop.host

import java.nio.file.FileSystems
import org.junit.Assume.assumeTrue

/**
 * True when this test JVM's filesystem is POSIX (macOS, Linux). The release workflow also runs
 * `dev.supermux.desktop.host.*` on Windows, where POSIX file modes do not exist, `Path.of("/a/b")`
 * renders as `\a\b`, and a read-only directory still lets its files be deleted.
 */
val posixHost: Boolean = "posix" in FileSystems.getDefault().supportedFileAttributeViews()

/**
 * For a test of a macOS/Linux scenario (launchd, systemd, XDG, D-Bus) whose fixtures are POSIX
 * paths and modes: those code paths never run on a Windows host, so there it is skipped, not failed.
 */
fun assumePosixHost() = assumeTrue("POSIX host only (macOS/Linux scenario)", posixHost)
