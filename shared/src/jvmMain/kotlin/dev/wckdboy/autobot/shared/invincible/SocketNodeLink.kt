package dev.wckdboy.autobot.shared.invincible

import java.io.EOFException
import java.net.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [NodeLink] over a connected [Socket]. The caller opens the socket the way the network policy
 * says (direct, or through Tor / SOCKS5 with remote DNS), after the kill switch allowed it.
 */
class SocketNodeLink(private val socket: Socket) : NodeLink {
    private val input = socket.getInputStream()
    private val output = socket.getOutputStream()

    override suspend fun readExactly(count: Int): ByteArray = withContext(Dispatchers.IO) {
        val out = ByteArray(count)
        var at = 0
        while (at < count) {
            val n = input.read(out, at, count - at)
            if (n < 0) throw EOFException("the node closed the connection")
            at += n
        }
        out
    }

    override suspend fun write(bytes: ByteArray) = withContext(Dispatchers.IO) {
        output.write(bytes)
        output.flush()
    }

    override fun close() = socket.close()
}
