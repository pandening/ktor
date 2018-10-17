package io.ktor.http.cio

import io.ktor.http.content.*
import io.ktor.http.*
import io.ktor.util.*
import kotlinx.coroutines.*
import kotlinx.coroutines.io.*
import kotlinx.io.core.*
import kotlinx.io.streams.*
import java.io.*
import java.nio.*
import java.nio.channels.*
import java.nio.file.*
import kotlin.coroutines.*

@Suppress("KDocMissingDocumentation", "unused")
@Deprecated("Use CIOMultipartDataBase instead", level = DeprecationLevel.ERROR)
typealias CIOMultipartData = CIOMultipartDataBase

/**
 * Represents a multipart data object that does parse and convert parts to ktor's [PartData]
 */
@InternalAPI
class CIOMultipartDataBase(
    override val coroutineContext: CoroutineContext,
    channel: ByteReadChannel,
    contentType: CharSequence,
    contentLength: Long?,
    private val formFieldLimit: Int = 65536,
    private val inMemoryFileUploadLimit: Int = formFieldLimit
) : MultiPartData, CoroutineScope {
    private val events = parseMultipart(channel, contentType, contentLength)

    override suspend fun readPart(): PartData? {
        while (true) {
            val event = events.receiveOrNull() ?: return null
            val part = eventToData(event)
            if (part != null) {
                return part
            }
        }
    }

    private suspend fun eventToData(evt: MultipartEvent): PartData? {
        return try {
            when (evt) {
                is MultipartEvent.MultipartPart -> partToData(evt)
                else -> {
                    evt.release()
                    null
                }
            }
        } catch (t: Throwable) {
            evt.release()
            throw t
        }
    }

    private suspend fun partToData(part: MultipartEvent.MultipartPart): PartData {
        val headers = part.headers.await()

        val contentDisposition = headers["Content-Disposition"]?.let { ContentDisposition.parse(it.toString()) }
        val filename = contentDisposition?.parameter("filename")

        if (filename != null) {
            // file upload
            val buffer = ByteBuffer.allocate(inMemoryFileUploadLimit)
            part.body.readAvailable(buffer)

            val completeRead = if (buffer.remaining() > 0) {
                part.body.readAvailable(buffer) == -1
            } else false

            buffer.flip()

            if (!completeRead) {
                val tmp = Files.createTempFile("file-upload-", ".tmp")
                FileChannel.open(
                    tmp,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE
                ).use { out ->
                    while (true) {
                        while (buffer.hasRemaining()) {
                            out.write(buffer)
                        }
                        buffer.clear()

                        if (part.body.readAvailable(buffer) == -1) break
                        buffer.flip()
                    }
                }

                return PartData.FileItem(
                    { FileInputStream(tmp.toFile()).asInput() },
                    { Files.deleteIfExists(tmp); part.release() },
                    CIOHeaders(headers)
                )
            } else {
                val input = ByteArrayInputStream(buffer.array(), buffer.arrayOffset(), buffer.remaining()).asInput()
                return PartData.FileItem({ input }, { part.release() }, CIOHeaders(headers))
            }
        } else {
            val packet = part.body.readRemaining(formFieldLimit.toLong()) // TODO fail if limit exceeded
            try {
                return PartData.FormItem(packet.readText(), { part.release() }, CIOHeaders(headers))
            } finally {
                packet.release()
            }
        }
    }
}
