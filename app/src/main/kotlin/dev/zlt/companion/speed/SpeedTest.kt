package dev.zlt.companion.speed

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Where the speed test aims, with the web dashboard's defaults: Cloudflare's
 * public speed backend. The byte counts are ceilings, not fixed costs; see
 * [SpeedTest].
 */
data class SpeedTestConfig(
    val downUrl: String = "https://speed.cloudflare.com/__down",
    val upUrl: String = "https://speed.cloudflare.com/__up",
    val downBytes: Long = 25_000_000,
    val upBytes: Long = 8_000_000,
)

class SpeedTestFailed(message: String) : Exception(message)

/**
 * An on-demand download / upload / latency test, ported from the web
 * dashboard's speed.js.
 *
 * It runs from this phone, so it measures this phone's path out: through the
 * router and over 4G when the phone is on the router's Wi-Fi. Both directions
 * are time-boxed rather than fixed-size, because the payload that takes two
 * seconds on a good cell takes minutes on a bad one. The download streams and
 * stops reading at [DOWN_CAP_SECONDS]; an upload cannot be measured mid-flight,
 * so a small probe sizes the real run to about [TARGET_SECONDS].
 *
 * This is the one part of the app that talks to anything but the router, and
 * it only does so when someone presses Run.
 */
class SpeedTest(
    private val config: SpeedTestConfig = SpeedTestConfig(),
    baseClient: OkHttpClient = OkHttpClient(),
    private val nanoClock: () -> Long = System::nanoTime,
) {

    private val http = baseClient.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    /** Best of [PING_SAMPLES] round trips, in ms: the floor is the honest one. */
    suspend fun ping(): Int = runInterruptible(Dispatchers.IO) {
        val request = get(sized(config.downUrl, 0))
        var best = Long.MAX_VALUE
        repeat(PING_SAMPLES) {
            val started = nanoClock()
            http.call(request, "Latency check") { it.body?.bytes() }
            best = minOf(best, nanoClock() - started)
        }
        (best / 1_000_000.0).roundToInt()
    }

    /**
     * Download Mbps. [onProgress] gets the running figure a few times a second
     * so the number moves while it runs.
     */
    suspend fun download(onProgress: (Double) -> Unit = {}): Double = runInterruptible(Dispatchers.IO) {
        val started = nanoClock()
        fun seconds() = (nanoClock() - started) / 1e9
        http.call(get(sized(config.downUrl, config.downBytes)), "Download") { response ->
            val source = response.body?.source() ?: throw SpeedTestFailed("Download returned nothing.")
            val chunk = ByteArray(CHUNK_BYTES)
            var got = 0L
            var painted = 0.0
            while (true) {
                val read = source.read(chunk)
                if (read == -1) break
                got += read
                val elapsed = seconds()
                if (elapsed >= DOWN_CAP_SECONDS) break // enough to measure with
                if (elapsed - painted > PAINT_EVERY_SECONDS) {
                    painted = elapsed
                    onProgress(mbps(got, elapsed))
                }
            }
            mbps(got, seconds())
        }
    }

    /** Upload Mbps, from a run sized off a small probe. */
    suspend fun upload(): Double = runInterruptible(Dispatchers.IO) {
        val floor = minOf(UP_PROBE_BYTES, config.upBytes)
        val probe = post(floor)
        val run = post(uploadSize(probe.bytes, probe.seconds, floor, config.upBytes))
        mbps(run.bytes, run.seconds)
    }

    private data class Timed(val bytes: Long, val seconds: Double)

    private fun post(bytes: Long): Timed {
        val request = Request.Builder()
            .url(config.upUrl)
            .header("Cache-Control", "no-store")
            .post(ZeroBody(bytes))
            .build()
        val started = nanoClock()
        http.call(request, "Upload") { it.body?.bytes() }
        return Timed(bytes, (nanoClock() - started) / 1e9)
    }

    private fun get(url: String) = Request.Builder()
        .url(url)
        .header("Cache-Control", "no-store")
        // Set by hand so OkHttp does not ask for gzip and then measure the
        // inflated size: a compressible payload would read as a faster link.
        .header("Accept-Encoding", "identity")
        .build()

    private fun <T> OkHttpClient.call(request: Request, what: String, read: (okhttp3.Response) -> T): T =
        try {
            newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw SpeedTestFailed("$what failed (${response.code}).")
                read(response)
            }
        } catch (e: IOException) {
            throw SpeedTestFailed("$what failed: could not reach the speed test server.")
        }

    /** A body of [length] zero bytes, written in chunks rather than held whole. */
    private class ZeroBody(private val length: Long) : RequestBody() {
        override fun contentType(): MediaType = OCTET_STREAM
        override fun contentLength(): Long = length
        override fun writeTo(sink: BufferedSink) {
            val zeros = ByteArray(CHUNK_BYTES)
            var left = length
            while (left > 0) {
                val n = minOf(left, zeros.size.toLong()).toInt()
                sink.write(zeros, 0, n)
                left -= n
            }
        }
    }

    companion object {
        const val TARGET_SECONDS = 5.0
        const val DOWN_CAP_SECONDS = 8.0
        const val UP_PROBE_BYTES = 512L * 1024
        const val PING_SAMPLES = 4
        private const val PAINT_EVERY_SECONDS = 0.2
        private const val CHUNK_BYTES = 64 * 1024
        private val OCTET_STREAM = "application/octet-stream".toMediaType()

        /** Megabits per second, to one decimal place. */
        fun mbps(bytes: Long, seconds: Double): Double =
            if (seconds > 0) (bytes * 8 / seconds / 1e6 * 10).roundToLong() / 10.0 else 0.0

        /** How big the real upload should be, given how fast the probe went. */
        fun uploadSize(probeBytes: Long, probeSeconds: Double, floor: Long, ceiling: Long): Long {
            val rate = if (probeSeconds > 0) probeBytes / probeSeconds else 0.0
            return (rate * TARGET_SECONDS).roundToLong().coerceIn(floor, maxOf(floor, ceiling))
        }

        fun sized(url: String, bytes: Long): String =
            url + (if ('?' in url) "&" else "?") + "bytes=" + bytes
    }
}
