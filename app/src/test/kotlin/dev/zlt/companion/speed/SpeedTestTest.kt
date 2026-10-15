package dev.zlt.companion.speed

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedTestTest {

    private val server = MockWebServer()
    private val seen = mutableListOf<RecordedRequest>()
    private var downStatus = 200

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                seen += request
                return when {
                    request.path!!.startsWith("/__down") -> {
                        val bytes = request.requestUrl!!.queryParameter("bytes")!!.toLong()
                        MockResponse().setResponseCode(downStatus).setBody(Buffer().write(ByteArray(bytes.toInt())))
                    }
                    else -> MockResponse().setResponseCode(200).setBody("{}")
                }
            }
        }
        server.start()
    }

    @After
    fun tearDown() = server.shutdown()

    private fun speedTest() = SpeedTest(
        SpeedTestConfig(
            downUrl = server.url("/__down").toString(),
            upUrl = server.url("/__up").toString(),
            downBytes = 200_000,
            upBytes = 600_000,
        ),
    )

    @Test
    fun `ping takes four samples of an empty download`() = runTest {
        val ms = speedTest().ping()
        assertTrue(ms >= 0)
        assertEquals(4, seen.size)
        assertTrue(seen.all { it.requestUrl!!.queryParameter("bytes") == "0" })
    }

    @Test
    fun `download asks for the configured ceiling without compression`() = runTest {
        val mbps = speedTest().download()
        assertTrue(mbps >= 0.0)
        val request = seen.single()
        assertEquals("200000", request.requestUrl!!.queryParameter("bytes"))
        assertEquals("identity", request.getHeader("Accept-Encoding"))
    }

    @Test
    fun `upload sends a probe and then a sized run within the bounds`() = runTest {
        speedTest().upload()
        assertEquals(2, seen.size)
        assertEquals(SpeedTest.UP_PROBE_BYTES, seen[0].bodySize)
        assertTrue(seen[1].bodySize in SpeedTest.UP_PROBE_BYTES..600_000L)
    }

    @Test
    fun `a failed transfer says so`() = runTest {
        downStatus = 503
        val error = runCatching { speedTest().download() }.exceptionOrNull()
        assertTrue("got $error", error is SpeedTestFailed)
    }

    @Test
    fun `mbps is megabits to one decimal`() {
        assertEquals(8.0, SpeedTest.mbps(1_000_000, 1.0), 0.0)
        assertEquals(26.7, SpeedTest.mbps(10_000_000, 3.0), 0.0)
        assertEquals(0.0, SpeedTest.mbps(1_000, 0.0), 0.0)
    }

    @Test
    fun `upload size aims at five seconds within the bounds`() {
        // 1 MB/s probe -> 5 MB run.
        assertEquals(5_000_000L, SpeedTest.uploadSize(1_000_000, 1.0, 524_288, 8_000_000))
        // A fast link is capped at the ceiling...
        assertEquals(8_000_000L, SpeedTest.uploadSize(10_000_000, 1.0, 524_288, 8_000_000))
        // ...and a slow one never goes below the probe size.
        assertEquals(524_288L, SpeedTest.uploadSize(10_000, 1.0, 524_288, 8_000_000))
    }

    @Test
    fun `sized appends to an existing query`() {
        assertEquals("https://x/__down?bytes=5", SpeedTest.sized("https://x/__down", 5))
        assertEquals("https://x/__down?a=1&bytes=5", SpeedTest.sized("https://x/__down?a=1", 5))
    }
}
