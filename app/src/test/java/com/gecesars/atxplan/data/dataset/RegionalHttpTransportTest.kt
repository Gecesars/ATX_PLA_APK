package com.gecesars.atxplan.data.dataset

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URI
import java.net.URL
import java.security.cert.Certificate
import javax.net.ssl.HttpsURLConnection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class RegionalHttpTransportTest {
    @Test
    fun `an initial URL above 2048 characters is rejected before opening a connection`() {
        val opened = mutableListOf<URI>()
        val transport = transport(opened)
        val oversizedUrl = providerUrlWithLength(2_049)

        val error = assertThrows(RegionalHttpSecurityException::class.java) {
            transport.execute(
                RegionalHttpRequest(
                    url = oversizedUrl,
                    method = RegionalHttpRequestMethod.GET,
                ),
            )
        }

        assertTrue(error.message.orEmpty().contains("length limit"))
        assertTrue(opened.isEmpty())
    }

    @Test
    fun `a redirect URL above 2048 characters is rejected before opening its connection`() {
        val oversizedRedirect = providerUrlWithLength(2_049)
        val first = FakeHttpsConnection(
            url = URL(OVERPASS_URL),
            fakeStatusCode = 307,
            headers = mapOf("Location" to oversizedRedirect),
        )
        val opened = mutableListOf<URI>()
        val transport = transport(opened, first)

        val error = assertThrows(RegionalHttpSecurityException::class.java) {
            transport.execute(postRequest())
        }

        assertTrue(error.message.orEmpty().contains("length limit"))
        assertEquals(listOf(URI(OVERPASS_URL)), opened)
        assertTrue(first.disconnected)
    }

    @Test
    fun `cross origin redirect is rejected before a second connection is opened`() {
        val first = FakeHttpsConnection(
            url = URL(OVERPASS_URL),
            fakeStatusCode = 307,
            headers = mapOf("Location" to WORLD_COVER_URL),
        )
        val opened = mutableListOf<URI>()
        val transport = transport(opened, first)

        val error = assertThrows(RegionalHttpSecurityException::class.java) {
            transport.execute(postRequest())
        }

        assertTrue(error.message.orEmpty().contains("cross-origin"))
        assertEquals(listOf(URI(OVERPASS_URL)), opened)
        assertTrue(first.disconnected)
    }

    @Test
    fun `same origin relative 307 preserves POST method and body`() {
        val first = FakeHttpsConnection(
            url = URL(OVERPASS_URL),
            fakeStatusCode = 307,
            headers = mapOf("Location" to "/api/redirected-interpreter"),
        )
        val second = FakeHttpsConnection(
            url = URL(REDIRECTED_OVERPASS_URL),
            fakeStatusCode = 200,
            headers = mapOf("Content-Length" to "2"),
            responseBytes = "{}".toByteArray(),
        )
        val opened = mutableListOf<URI>()
        val transport = transport(opened, first, second)
        val request = postRequest()

        transport.execute(request).use { response ->
            assertEquals(200, response.statusCode)
            assertEquals(REDIRECTED_OVERPASS_URL, response.finalUrl)
        }

        assertEquals(listOf(URI(OVERPASS_URL), URI(REDIRECTED_OVERPASS_URL)), opened)
        assertEquals("POST", first.requestMethod)
        assertEquals("POST", second.requestMethod)
        assertEquals(request.body, first.writtenBody.toString(Charsets.UTF_8.name()))
        assertEquals(request.body, second.writtenBody.toString(Charsets.UTF_8.name()))
        assertTrue(first.disconnected)
        assertTrue(second.disconnected)
    }

    @Test
    fun `unsafe Retry-After response header is dropped`() {
        val connection = FakeHttpsConnection(
            url = URL(OVERPASS_URL),
            fakeStatusCode = 429,
            headers = mapOf("Retry-After" to "5\r\nInjected: value"),
        )
        val transport = transport(mutableListOf(), connection)

        transport.execute(postRequest()).use { response ->
            assertNull(response.retryAfter)
        }
    }

    @Test
    fun `bounded printable Retry-After response header is exposed`() {
        val connection = FakeHttpsConnection(
            url = URL(OVERPASS_URL),
            fakeStatusCode = 429,
            headers = mapOf("Retry-After" to " 120 "),
        )
        val transport = transport(mutableListOf(), connection)

        transport.execute(postRequest()).use { response ->
            assertEquals("120", response.retryAfter)
        }
    }

    private fun transport(
        opened: MutableList<URI>,
        vararg connections: FakeHttpsConnection,
    ): AllowlistedHttpsRegionalHttpTransport {
        var index = 0
        return AllowlistedHttpsRegionalHttpTransport(
            testConnectionFactory = RegionalHttpsConnectionFactory { uri ->
                opened += uri
                if (index >= connections.size) {
                    throw AssertionError("The transport opened an unexpected connection to $uri.")
                }
                connections[index++]
            },
            allowedHosts = setOf(
                "lambert.openstreetmap.de",
                "esa-worldcover.s3.eu-central-1.amazonaws.com",
            ),
        )
    }

    private fun postRequest(): RegionalHttpRequest = RegionalHttpRequest(
        url = OVERPASS_URL,
        method = RegionalHttpRequestMethod.POST,
        body = "data=way%5Bbuilding%5D%3Bout+geom%3B",
        contentType = "application/x-www-form-urlencoded; charset=UTF-8",
    )

    private fun providerUrlWithLength(length: Int): String {
        val prefix = "$OVERPASS_URL?payload="
        require(length > prefix.length)
        return prefix + "x".repeat(length - prefix.length)
    }
}

private class FakeHttpsConnection(
    url: URL,
    private val fakeStatusCode: Int,
    private val headers: Map<String, String> = emptyMap(),
    private val responseBytes: ByteArray = ByteArray(0),
) : HttpsURLConnection(url) {
    val writtenBody = ByteArrayOutputStream()
    var disconnected = false
        private set

    override fun connect() = Unit

    override fun disconnect() {
        disconnected = true
    }

    override fun usingProxy(): Boolean = false

    override fun getResponseCode(): Int = fakeStatusCode

    override fun getHeaderField(name: String?): String? = headers.entries
        .firstOrNull { (headerName, _) -> headerName.equals(name, ignoreCase = true) }
        ?.value

    override fun getInputStream(): InputStream = ByteArrayInputStream(responseBytes)

    override fun getErrorStream(): InputStream = ByteArrayInputStream(responseBytes)

    override fun getOutputStream(): ByteArrayOutputStream = writtenBody

    override fun getCipherSuite(): String = "TLS_FAKE_WITH_NULL_NULL"

    override fun getLocalCertificates(): Array<Certificate>? = null

    override fun getServerCertificates(): Array<Certificate> = emptyArray()
}

private const val OVERPASS_URL = "https://lambert.openstreetmap.de/api/interpreter"
private const val REDIRECTED_OVERPASS_URL =
    "https://lambert.openstreetmap.de/api/redirected-interpreter"
private const val WORLD_COVER_URL =
    "https://esa-worldcover.s3.eu-central-1.amazonaws.com/v200/2021/map/tile.tif"
