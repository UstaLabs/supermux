// #117 S1: one supermux per user. An applet's shortcut (a Linux .desktop entry, a Windows Start-menu link, a macOS
// stub app through the supermux:// scheme) starts the app with the applet's address; when supermux is already running,
// that second process hands the address to the first over a local socket and exits, and the first opens the applet's
// window (or brings it to the front). A plain second launch hands over "activate": the running window comes forward
// instead of a second app.
package dev.supermux.desktop.applets

import dev.supermux.ui.applets.APPLET_URI_PREFIX
import dev.supermux.ui.applets.parseAppletUri
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

internal object AppletHandOff {
    /** The applet address in the launch arguments: `supermux://applet/<host>/<name>` itself, or `--applet=<that>`. */
    fun appletArg(args: Array<String>): String? =
        args.asSequence()
            .map { it.removePrefix("--applet=") }
            .firstOrNull { it.startsWith(APPLET_URI_PREFIX) && parseAppletUri(it) != null }

    /**
     * Hands [message] ("open <address>" or "activate") to the supermux already running for this user, if one listens
     * on [socket]. True when it took it: this process should exit.
     */
    fun handOff(socket: Path, message: String): Boolean {
        if (!Files.exists(socket)) return false
        return runCatching {
            SocketChannel.open(StandardProtocolFamily.UNIX).use { ch ->
                ch.connect(UnixDomainSocketAddress.of(socket))
                ch.write(ByteBuffer.wrap((message + "\n").toByteArray(StandardCharsets.UTF_8)))
                // The running app answers "ok" once it has the message; a dead one never accepts.
                val reply = ByteBuffer.allocate(8)
                ch.read(reply)
                String(reply.array(), 0, reply.position(), StandardCharsets.UTF_8).startsWith("ok")
            }
        }.getOrDefault(false)
    }

    /**
     * Listens on [socket] for the next launches' messages, on a daemon thread; [onOpen] gets an applet's
     * (host, name), [onActivate] a plain launch. A socket file left by a crashed app is replaced. False when the socket
     * can't be made (the app then runs as before, just without the hand-off).
     */
    fun listen(socket: Path, onOpen: (String, String) -> Unit, onActivate: () -> Unit): Boolean {
        val server = runCatching {
            Files.createDirectories(socket.parent)
            Files.deleteIfExists(socket)
            ServerSocketChannel.open(StandardProtocolFamily.UNIX).also { it.bind(UnixDomainSocketAddress.of(socket)) }
        }.getOrElse {
            println("[AppletHandOff] can't listen on $socket: ${it.message}")
            return false
        }
        runCatching { socket.toFile().deleteOnExit() }
        Thread({
            while (true) {
                val ch = runCatching { server.accept() }.getOrNull() ?: break
                runCatching {
                    ch.use {
                        val line = Channels.newReader(ch, StandardCharsets.UTF_8).buffered().readLine().orEmpty()
                        ch.write(ByteBuffer.wrap("ok\n".toByteArray(StandardCharsets.UTF_8)))
                        when {
                            line.startsWith("open ") -> parseAppletUri(line.removePrefix("open ").trim())?.let { (h, n) -> onOpen(h, n) }
                            line == "activate" -> onActivate()
                        }
                    }
                }
            }
        }, "applet-hand-off").apply { isDaemon = true }.start()
        return true
    }
}
