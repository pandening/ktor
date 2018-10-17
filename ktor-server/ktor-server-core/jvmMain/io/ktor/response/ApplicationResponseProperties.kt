@file:Suppress("unused")

package io.ktor.response

import io.ktor.http.*
import java.time.*
import java.time.temporal.*

@Suppress("UNUSED_PARAMETER", "KDocMissingDocumentation", "DeprecatedCallableAddReplaceWith")
@Deprecated(
    "Use respondText/respondBytes with content type or send respond object with the specified content type",
    level = DeprecationLevel.ERROR
)
fun ApplicationResponse.contentType(value: ContentType): Unit = throw UnsafeHeaderException(HttpHeaders.ContentType)

@Suppress("UNUSED_PARAMETER", "KDocMissingDocumentation", "DeprecatedCallableAddReplaceWith")
@Deprecated(
    "Use respondText/respondBytes with content type or send respond object with the specified content type",
    level = DeprecationLevel.ERROR
)
fun ApplicationResponse.contentType(value: String): Unit = throw UnsafeHeaderException(HttpHeaders.ContentType)

@Suppress("UNUSED_PARAMETER", "KDocMissingDocumentation", "DeprecatedCallableAddReplaceWith")
@Deprecated(
    "Use respondText/respondBytes or send respond object with the specified content length",
    level = DeprecationLevel.ERROR
)
fun ApplicationResponse.contentLength(length: Long): Unit = throw UnsafeHeaderException(HttpHeaders.ContentType)

/**
 * Append HTTP response header with string [value]
 */
fun ApplicationResponse.header(name: String, value: String): Unit = headers.append(name, value)

/**
 * Append HTTP response header with integer numeric [value]
 */
fun ApplicationResponse.header(name: String, value: Int): Unit = headers.append(name, value.toString())

/**
 * Append HTTP response header with long integer numeric [value]
 */
fun ApplicationResponse.header(name: String, value: Long): Unit = headers.append(name, value.toString())

/**
 * Append HTTP response header with temporal [date] (date, time and so on)
 */
fun ApplicationResponse.header(name: String, date: Temporal): Unit = headers.append(name, date.toHttpDateString())

/**
 * Append response `E-Tag` HTTP header [value]
 */
fun ApplicationResponse.etag(value: String): Unit = header(HttpHeaders.ETag, value)

/**
 * Append response `Last-Modified` HTTP header value from [dateTime]
 */
fun ApplicationResponse.lastModified(dateTime: ZonedDateTime): Unit = header(HttpHeaders.LastModified, dateTime)

/**
 * Append response `Cache-Control` HTTP header [value]
 */
fun ApplicationResponse.cacheControl(value: CacheControl): Unit = header(HttpHeaders.CacheControl, value.toString())

/**
 * Append response `Expires` HTTP header [value]
 */
fun ApplicationResponse.expires(value: LocalDateTime): Unit = header(HttpHeaders.Expires, value)

/**
 * Append `Cache-Control` HTTP header [value]
 */
fun HeadersBuilder.cacheControl(value: CacheControl): Unit = set(HttpHeaders.CacheControl, value.toString())

/**
 * Append 'Content-Range` header with specified [range] and [fullLength]
 */
fun HeadersBuilder.contentRange(
    range: LongRange?,
    fullLength: Long? = null,
    unit: String = RangeUnits.Bytes.unitToken
) {
    append(HttpHeaders.ContentRange, contentRangeHeaderValue(range, fullLength, unit))
}

/**
 * Append response `Content-Range` header with specified [range] and [fullLength]
 */
fun ApplicationResponse.contentRange(
    range: LongRange?,
    fullLength: Long? = null,
    unit: RangeUnits
) {
    contentRange(range, fullLength, unit.unitToken)
}

/**
 * Append response `Content-Range` header with specified [range] and [fullLength]
 */
fun ApplicationResponse.contentRange(
    range: LongRange?,
    fullLength: Long? = null,
    unit: String = RangeUnits.Bytes.unitToken
) {
    header(HttpHeaders.ContentRange, contentRangeHeaderValue(range, fullLength, unit))
}
