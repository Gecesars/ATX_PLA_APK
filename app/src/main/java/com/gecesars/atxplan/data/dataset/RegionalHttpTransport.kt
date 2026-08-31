package com.gecesars.atxplan.data.dataset

import java.io.Closeable
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.nio.charset.StandardCharsets
import javax.net.ssl.HttpsURLConnection

enum class RegionalHttpRequestMethod {
    GET,
    POST,
}

data class RegionalHttpRequest(
    val url: String,
    val method: RegionalHttpRequestMethod,
    val rangeStart: Long? = null,
    val ifRangeEtag: String? = null,
    val body: String? = null,
    val contentType: String? = null,
    val accept: String? = null,
) {
    init {
        require(rangeStart == null || rangeStart >= 0L) {
            "The HTTP range start must not be negative."
        }
        require(method == RegionalHttpRequestMethod.GET || rangeStart == null) {
            "Only GET requests may use byte ranges."
        }
        require(method == RegionalHttpRequestMethod.GET || ifRangeEtag == null) {
            "Only GET requests may use an If-Range validator."
        }
        require(method == RegionalHttpRequestMethod.POST || body == null) {
            "Only POST requests may include a body."
        }
        require(accept == null || (accept.isNotBlank() && isSafeHeaderValue(accept))) {
            "The HTTP Accept header is invalid."
        }
    }
}

class RegionalHttpResponse(
    val statusCode: Int,
    val finalUrl: String,
    val contentLength: Long?,
    val contentRange: String?,
    val etag: String?,
    val lastModified: String?,
    /** Bounded, printable raw Retry-After value. Interpretation remains a repository policy. */
    val retryAfter: String? = null,
    val body: InputStream,
    private val closeAction: () -> Unit,
) : Closeable {
    override fun close() {
        try {
            body.close()
        } finally {
            closeAction()
        }
    }
}

fun interface RegionalHttpTransport {
    fun execute(request: RegionalHttpRequest): RegionalHttpResponse
}

internal fun interface RegionalHttpsConnectionFactory {
    fun open(uri: URI): HttpsURLConnection
}

/**
 * Blocking HTTPS transport for the fixed regional-data providers.
 *
 * Redirects are deliberately handled by this class instead of the platform so every target is
 * checked against HTTPS, the host allowlist, and the original request origin before it is opened.
 * Callers run it on an IO dispatcher.
 */
class AllowlistedHttpsRegionalHttpTransport private constructor(
    allowedHosts: Set<String> = DEFAULT_REGIONAL_DATA_HOSTS,
    private val connectTimeoutMillis: Int = DEFAULT_CONNECT_TIMEOUT_MILLIS,
    private val readTimeoutMillis: Int = DEFAULT_READ_TIMEOUT_MILLIS,
    private val maximumRedirects: Int = DEFAULT_MAXIMUM_REDIRECTS,
    private val userAgent: String = DEFAULT_USER_AGENT,
    private val connectionFactory: RegionalHttpsConnectionFactory,
) : RegionalHttpTransport {
    constructor(
        allowedHosts: Set<String> = DEFAULT_REGIONAL_DATA_HOSTS,
        connectTimeoutMillis: Int = DEFAULT_CONNECT_TIMEOUT_MILLIS,
        readTimeoutMillis: Int = DEFAULT_READ_TIMEOUT_MILLIS,
        maximumRedirects: Int = DEFAULT_MAXIMUM_REDIRECTS,
        userAgent: String = DEFAULT_USER_AGENT,
    ) : this(
        allowedHosts = allowedHosts,
        connectTimeoutMillis = connectTimeoutMillis,
        readTimeoutMillis = readTimeoutMillis,
        maximumRedirects = maximumRedirects,
        userAgent = userAgent,
        connectionFactory = SYSTEM_HTTPS_CONNECTION_FACTORY,
    )

    internal constructor(
        testConnectionFactory: RegionalHttpsConnectionFactory,
        allowedHosts: Set<String> = DEFAULT_REGIONAL_DATA_HOSTS,
        connectTimeoutMillis: Int = DEFAULT_CONNECT_TIMEOUT_MILLIS,
        readTimeoutMillis: Int = DEFAULT_READ_TIMEOUT_MILLIS,
        maximumRedirects: Int = DEFAULT_MAXIMUM_REDIRECTS,
        userAgent: String = DEFAULT_USER_AGENT,
    ) : this(
        allowedHosts = allowedHosts,
        connectTimeoutMillis = connectTimeoutMillis,
        readTimeoutMillis = readTimeoutMillis,
        maximumRedirects = maximumRedirects,
        userAgent = userAgent,
        connectionFactory = testConnectionFactory,
    )

    private val allowedHosts = allowedHosts.mapTo(linkedSetOf()) { host ->
        normalizeHost(host)
    }

    init {
        require(this.allowedHosts.isNotEmpty()) {
            "At least one regional-data host must be allowed."
        }
        require(connectTimeoutMillis in 1..MAXIMUM_TIMEOUT_MILLIS) {
            "The connection timeout is outside the supported range."
        }
        require(readTimeoutMillis in 1..MAXIMUM_TIMEOUT_MILLIS) {
            "The read timeout is outside the supported range."
        }
        require(maximumRedirects in 0..MAXIMUM_REDIRECTS) {
            "The redirect limit is outside the supported range."
        }
        require(
            userAgent.isNotBlank() &&
                userAgent.length <= MAXIMUM_USER_AGENT_LENGTH &&
                isSafeHeaderValue(userAgent)
        ) {
            "The regional-data user agent is invalid."
        }
    }

    override fun execute(request: RegionalHttpRequest): RegionalHttpResponse {
        var currentUri = checkedProviderUri(request.url)
        val requestOrigin = currentUri.httpsOrigin()
        var currentMethod = request.method
        var currentBody = request.body
        var redirects = 0

        while (true) {
            val connection = connectionFactory.open(currentUri)
            configure(
                connection = connection,
                request = request,
                method = currentMethod,
                body = currentBody,
            )
            try {
                if (currentMethod == RegionalHttpRequestMethod.POST) {
                    val payload = currentBody.orEmpty().toByteArray(StandardCharsets.UTF_8)
                    connection.outputStream.use { output -> output.write(payload) }
                }
                val status = connection.responseCode
                if (status in REDIRECT_STATUS_CODES) {
                    if (redirects >= maximumRedirects) {
                        throw RegionalHttpSecurityException("The regional-data redirect limit was exceeded.")
                    }
                    val location = connection.getHeaderField("Location")
                        ?.takeIf(String::isNotBlank)
                        ?: throw RegionalHttpSecurityException(
                            "The regional-data redirect did not include a target.",
                        )
                    val nextUri = checkedProviderUri(currentUri.resolve(location))
                    if (nextUri.httpsOrigin() != requestOrigin) {
                        throw RegionalHttpSecurityException(
                            "The regional-data provider attempted a cross-origin redirect.",
                        )
                    }
                    val redirectMethod = redirectedMethod(status, currentMethod)
                    closeRedirectConnection(connection)
                    currentUri = nextUri
                    currentMethod = redirectMethod
                    if (redirectMethod == RegionalHttpRequestMethod.GET) currentBody = null
                    redirects += 1
                    continue
                }

                val responseBody = try {
                    if (status >= HttpURLConnection.HTTP_BAD_REQUEST) {
                        connection.errorStream ?: ByteArrayInputStream(ByteArray(0))
                    } else {
                        connection.inputStream
                    }
                } catch (error: Exception) {
                    connection.disconnect()
                    throw error
                }
                return RegionalHttpResponse(
                    statusCode = status,
                    finalUrl = currentUri.toASCIIString(),
                    contentLength = connection.getHeaderField("Content-Length")
                        ?.toLongOrNull()
                        ?.takeIf { value -> value >= 0L },
                    contentRange = connection.getHeaderField("Content-Range"),
                    etag = connection.getHeaderField("ETag")?.takeIf(::isSafeHeaderValue),
                    lastModified = connection.getHeaderField("Last-Modified")
                        ?.takeIf(::isSafeHeaderValue),
                    retryAfter = safeRetryAfter(connection.getHeaderField("Retry-After")),
                    body = responseBody,
                    closeAction = connection::disconnect,
                )
            } catch (error: Exception) {
                connection.disconnect()
                throw error
            }
        }
    }

    private fun configure(
        connection: HttpsURLConnection,
        request: RegionalHttpRequest,
        method: RegionalHttpRequestMethod,
        body: String?,
    ) {
        connection.instanceFollowRedirects = false
        connection.connectTimeout = connectTimeoutMillis
        connection.readTimeout = readTimeoutMillis
        connection.useCaches = false
        connection.requestMethod = method.name
        connection.setRequestProperty("Accept-Encoding", "identity")
        connection.setRequestProperty("User-Agent", userAgent)
        connection.setRequestProperty(
            "Accept",
            request.accept ?: "application/octet-stream, application/json;q=0.9",
        )

        if (method == RegionalHttpRequestMethod.GET) {
            request.rangeStart?.let { start ->
                connection.setRequestProperty("Range", "bytes=$start-")
                request.ifRangeEtag?.takeIf(::isSafeHeaderValue)?.let { etag ->
                    connection.setRequestProperty("If-Range", etag)
                }
            }
        } else {
            val payloadLength = body.orEmpty().toByteArray(StandardCharsets.UTF_8).size
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(payloadLength)
            connection.setRequestProperty(
                "Content-Type",
                request.contentType?.takeIf(::isSafeHeaderValue)
                    ?: "application/x-www-form-urlencoded; charset=utf-8",
            )
        }
    }

    private fun checkedProviderUri(rawUrl: String): URI = try {
        if (rawUrl.length !in 1..MAXIMUM_URL_LENGTH || rawUrl.any(Char::isISOControl)) {
            throw RegionalHttpSecurityException("The regional-data URL is invalid or exceeds its length limit.")
        }
        checkedProviderUri(URI(rawUrl))
    } catch (error: RegionalHttpSecurityException) {
        throw error
    } catch (error: Exception) {
        throw RegionalHttpSecurityException("The regional-data URL is invalid.", error)
    }

    private fun checkedProviderUri(uri: URI): URI {
        val serialized = uri.toASCIIString()
        if (
            serialized.length !in 1..MAXIMUM_URL_LENGTH ||
            serialized.any(Char::isISOControl)
        ) {
            throw RegionalHttpSecurityException("The regional-data URL is invalid or exceeds its length limit.")
        }
        if (!uri.isAbsolute || !uri.scheme.equals("https", ignoreCase = true)) {
            throw RegionalHttpSecurityException("The regional-data endpoint must use HTTPS.")
        }
        if (uri.rawUserInfo != null || uri.rawFragment != null) {
            throw RegionalHttpSecurityException("The regional-data URL contains forbidden components.")
        }
        if (uri.port !in setOf(-1, DEFAULT_HTTPS_PORT)) {
            throw RegionalHttpSecurityException("The regional-data endpoint uses an unsupported port.")
        }
        val host = normalizeHost(uri.host ?: throw RegionalHttpSecurityException(
            "The regional-data URL does not contain a valid host.",
        ))
        if (host !in allowedHosts) {
            throw RegionalHttpSecurityException("The regional-data host is not approved.")
        }
        return uri
    }

    private fun redirectedMethod(
        status: Int,
        currentMethod: RegionalHttpRequestMethod,
    ): RegionalHttpRequestMethod {
        if (currentMethod == RegionalHttpRequestMethod.GET) return currentMethod
        return when (status) {
            HTTP_TEMPORARY_REDIRECT,
            HTTP_PERMANENT_REDIRECT,
            -> RegionalHttpRequestMethod.POST

            else -> throw RegionalHttpSecurityException(
                "The provider attempted to change the method of a POST request.",
            )
        }
    }

    private fun closeRedirectConnection(connection: HttpsURLConnection) {
        try {
            connection.inputStream?.close()
        } catch (_: Exception) {
            try {
                connection.errorStream?.close()
            } catch (_: Exception) {
                // The connection is disconnected below.
            }
        } finally {
            connection.disconnect()
        }
    }
}

private data class HttpsOrigin(
    val scheme: String,
    val host: String,
    val effectivePort: Int,
)

private fun URI.httpsOrigin(): HttpsOrigin = HttpsOrigin(
    scheme = scheme.lowercase(),
    host = normalizeHost(requireNotNull(host)),
    effectivePort = if (port == -1) DEFAULT_HTTPS_PORT else port,
)

internal class RegionalHttpSecurityException(
    message: String,
    cause: Throwable? = null,
) : java.io.IOException(message, cause)

private fun normalizeHost(host: String): String {
    val normalized = host.trim().lowercase().trimEnd('.')
    require(normalized.isNotEmpty() && normalized.length <= MAXIMUM_HOST_LENGTH) {
        "A regional-data host is invalid."
    }
    return normalized
}

private fun isSafeHeaderValue(value: String): Boolean =
    value.length <= MAXIMUM_HEADER_VALUE_LENGTH && '\r' !in value && '\n' !in value

private fun safeRetryAfter(value: String?): String? = value
    ?.trim()
    ?.takeIf { candidate ->
        candidate.length in 1..MAXIMUM_RETRY_AFTER_LENGTH &&
            candidate.all { character -> character.code in PRINTABLE_ASCII_RANGE }
    }

private val REDIRECT_STATUS_CODES = setOf(
    HttpURLConnection.HTTP_MOVED_PERM,
    HttpURLConnection.HTTP_MOVED_TEMP,
    HttpURLConnection.HTTP_SEE_OTHER,
    HTTP_TEMPORARY_REDIRECT,
    HTTP_PERMANENT_REDIRECT,
)

internal val DEFAULT_REGIONAL_DATA_HOSTS = setOf(
    "copernicus-dem-30m.s3.eu-central-1.amazonaws.com",
    "esa-worldcover.s3.eu-central-1.amazonaws.com",
    "lambert.openstreetmap.de",
)

private const val DEFAULT_CONNECT_TIMEOUT_MILLIS = 20_000
private const val DEFAULT_READ_TIMEOUT_MILLIS = 45_000
private const val MAXIMUM_TIMEOUT_MILLIS = 120_000
private const val DEFAULT_MAXIMUM_REDIRECTS = 4
private const val MAXIMUM_REDIRECTS = 8
private const val DEFAULT_HTTPS_PORT = 443
private const val HTTP_TEMPORARY_REDIRECT = 307
private const val HTTP_PERMANENT_REDIRECT = 308
private const val MAXIMUM_HOST_LENGTH = 253
private const val MAXIMUM_URL_LENGTH = 2_048
private const val MAXIMUM_USER_AGENT_LENGTH = 256
private const val MAXIMUM_HEADER_VALUE_LENGTH = 2_048
private const val MAXIMUM_RETRY_AFTER_LENGTH = 128
private const val DEFAULT_USER_AGENT = "ATX-Plan-Android/0.1 regional-data-client"
private val PRINTABLE_ASCII_RANGE = 0x20..0x7e

private val SYSTEM_HTTPS_CONNECTION_FACTORY = RegionalHttpsConnectionFactory { uri ->
    (uri.toURL().openConnection() as? HttpsURLConnection)
        ?: throw RegionalHttpSecurityException("The regional-data endpoint is not HTTPS.")
}
