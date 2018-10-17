package io.ktor.network.tls

import io.ktor.http.cio.internals.*
import kotlinx.io.core.*
import kotlinx.io.core.Closeable
import java.security.*

internal class Digest : Closeable {
    private val state = BytePacketBuilder()

    fun update(packet: ByteReadPacket) = synchronized(this) {
        if (packet.isEmpty) return
        state.writePacket(packet.copy())
    }

    fun doHash(hashName: String): ByteArray = synchronized(this) {
        state.preview { handshakes: ByteReadPacket ->
            val digest = MessageDigest.getInstance(hashName)!!

            val buffer = DefaultByteBufferPool.borrow()
            try {
                while (!handshakes.isEmpty) {
                    val rc = handshakes.readAvailable(buffer)
                    if (rc == -1) break
                    buffer.flip()
                    digest.update(buffer)
                    buffer.clear()
                }

                return@preview digest.digest()
            } finally {
                DefaultByteBufferPool.recycle(buffer)
            }
        }
    }

    override fun close() {
        state.release()
    }

}

internal operator fun Digest.plusAssign(record: TLSHandshake) {
    check(record.type != TLSHandshakeType.HelloRequest)

    update(buildPacket {
        writeTLSHandshakeType(record.type, record.packet.remaining.toInt())
        if (record.packet.remaining > 0) writePacket(record.packet.copy())
    })
}
