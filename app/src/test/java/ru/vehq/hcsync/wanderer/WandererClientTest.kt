package ru.vehq.hcsync.wanderer

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class WandererClientTest {
    private val server = MockWebServer()
    private var token: String? = "wanderer_key_test123"

    @Before fun start() = server.start()
    @After fun stop() = server.shutdown()

    private fun client(base: String = server.url("/").toString()) = OkHttpWandererClient(base, { token })

    @Test
    fun baseUrlNormalization() {
        for (raw in listOf("https://wanderer.example.com", "https://wanderer.example.com/", "https://wanderer.example.com/api/v1", " https://wanderer.example.com/api/v1/ ")) {
            assertEquals("https://wanderer.example.com/api/v1/", OkHttpWandererClient.normalizeBaseUrl(raw).toString())
        }
        assertEquals("http://host:8080/sub/api/v1/", OkHttpWandererClient.normalizeBaseUrl("http://host:8080/sub").toString())
    }

    @Test
    fun uploadSendsMultipartGpxWithBearerAndReturnsTrailId() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"id":"abc123","name":"Walk"}"""))
        val id = client().uploadGpx("<gpx/>", "walk.gpx")
        assertEquals("abc123", id)

        val req = server.takeRequest()
        assertEquals("PUT", req.method)
        assertEquals("/api/v1/trail/upload", req.path)
        assertEquals("Bearer wanderer_key_test123", req.getHeader("Authorization"))
        assertTrue(req.getHeader("Content-Type")!!.startsWith("multipart/form-data"))
        val body = req.body.readUtf8()
        assertTrue(body.contains("Content-Disposition: form-data; name=\"file\"; filename=\"walk.gpx\""))
        assertTrue(body.contains("Content-Type: application/gpx+xml"))
        assertTrue(body.contains("<gpx/>"))
        assertTrue(body.contains("name=\"ignoreDuplicates\"") && body.contains("\r\n\r\ntrue\r\n"))
    }

    @Test
    fun rejectedTokenIs401WithServerMessage() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"message":"invalid_token"}"""))
        try {
            client().uploadGpx("<gpx/>", "walk.gpx")
            fail("expected WandererHttpException")
        } catch (e: WandererHttpException) {
            assertEquals(401, e.status)
            assertEquals("invalid_token", e.serverMessage)
        }
        assertEquals(1, server.requestCount) // no retry: a bad token stays bad
    }

    @Test
    fun missingTokenFailsBeforeAnyRequest() = runTest {
        token = "  "
        try {
            client().testConnection()
            fail("expected WandererHttpException")
        } catch (e: WandererHttpException) {
            assertEquals(401, e.status)
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun badRequestSurfacesWandererMessage() = runTest {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"message":"Invalid file"}"""))
        try {
            client().uploadGpx("not xml", "x.gpx")
            fail("expected WandererHttpException")
        } catch (e: WandererHttpException) {
            assertEquals(400, e.status)
            assertEquals("Invalid file", e.serverMessage)
        }
    }

    @Test
    fun findTrailByRecordIdFiltersOnDescription() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"items":[{"id":"t42","name":"Walk"}],"totalItems":1}"""))
        assertEquals("t42", client().findTrailByRecordId("0f3a6a4e-1111-2222-3333-444455556666"))
        val req = server.takeRequest()
        assertEquals("GET", req.method)
        assertEquals("/api/v1/trail", req.requestUrl!!.encodedPath)
        assertEquals("description ~ \"0f3a6a4e-1111-2222-3333-444455556666\"", req.requestUrl!!.queryParameter("filter"))
        assertEquals("1", req.requestUrl!!.queryParameter("perPage"))
        assertEquals("Bearer wanderer_key_test123", req.getHeader("Authorization"))
    }

    @Test
    fun findTrailByRecordIdReturnsNullWhenNothingMatches() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"items":[],"totalItems":0}"""))
        assertEquals(null, client().findTrailByRecordId("nope"))
    }

    @Test
    fun testConnectionListsOwnTokenName() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"items":[{"id":"t1","name":"phone"}],"page":1}"""))
        assertEquals("Connected (token \"phone\")", client().testConnection())
        val req = server.takeRequest()
        assertEquals("GET", req.method)
        assertEquals("/api/v1/api-token?perPage=1", req.path)
        assertEquals("Bearer wanderer_key_test123", req.getHeader("Authorization"))
    }
}
