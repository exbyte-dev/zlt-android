package dev.zlt.companion.net

import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mirrors tests/test_client_ussd.py from the Python project. */
class ZltUssdTest {

    private val router = RouterMock().apply { values["token"] = "1" }

    @After
    fun tearDown() = router.close()

    private fun reply(text: String = UCS2_HI, action: String = "0") = mapOf(
        "ussd_data" to text, "ussd_action" to action, "ussd_dcs" to "72",
    )

    // --- flag classification -------------------------------------------------

    @Test
    fun `flag 16 is received`() =
        assertEquals(UssdFlag.Received, classifyUssdFlag(mapOf("ussd_write_flag" to "16")))

    @Test
    fun `flag 15 is pending`() =
        assertEquals(UssdFlag.Pending, classifyUssdFlag(mapOf("ussd_write_flag" to "15")))

    @Test
    fun `timeout flags`() {
        for (flag in listOf("3", "4", "unknown")) {
            assertEquals(UssdFlag.Timeout, classifyUssdFlag(mapOf("ussd_write_flag" to flag)))
        }
    }

    @Test
    fun `error flags carry their reason`() {
        assertEquals(UssdFlag.Error("network terminated"), classifyUssdFlag(mapOf("ussd_write_flag" to "2")))
        assertEquals(UssdFlag.Error("no service"), classifyUssdFlag(mapOf("ussd_write_flag" to "1")))
    }

    @Test
    fun `a missing flag key is a contract mismatch`() {
        assertThrows(UnsupportedResponse::class.java) { classifyUssdFlag(mapOf("ussd_data" to "x")) }
    }

    // --- end to end ----------------------------------------------------------

    @Test
    fun `one shot reply completes`() = runTest {
        router.ussdFlags = listOf("15", "16")
        router.ussdData = reply()

        assertEquals(UssdResult("Hi", UssdState.COMPLETE), router.client().ussdSend("*310#"))
    }

    @Test
    fun `action 1 is a prompt`() = runTest {
        router.ussdFlags = listOf("16")
        router.ussdData = reply(action = "1")

        assertEquals(UssdState.PROMPT, router.client().ussdSend("*312#").state)
    }

    @Test
    fun `timeout flag times out`() = runTest {
        router.ussdFlags = listOf("3")
        assertEquals(UssdState.TIMEOUT, router.client().ussdSend("*310#").state)
    }

    @Test
    fun `endless pending gives up at the deadline`() = runTest {
        router.ussdFlags = listOf("15")

        assertEquals(UssdResult("", UssdState.TIMEOUT), router.client().ussdSend("*310#"))
        // 20s at 1s intervals: the first poll plus twenty more, and no more.
        assertEquals(21, router.getsFor("ussd_write_flag").size)
    }

    @Test
    fun `error flag reports the reason`() = runTest {
        router.ussdFlags = listOf("2")

        val result = router.client().ussdSend("*310#")
        assertEquals(UssdState.ERROR, result.state)
        assertTrue(result.text.contains("network terminated"))
    }

    @Test
    fun `a poll with no flag key fails fast`() = runTest {
        // A device whose poll answer has no ussd_write_flag key at all.
        router.answerRawGet("{\"token\":\"1\"}")

        val error = runCatching { router.client().ussdSend("*310#") }.exceptionOrNull()
        assertTrue("got $error", error is UnsupportedResponse)
    }

    @Test
    fun `send posts the captured body`() = runTest {
        router.ussdFlags = listOf("16")
        router.ussdData = reply()

        router.client().ussdSend("*310#")

        val body = router.postsSeen.first()
        assertTrue(body.contains("goformId=USSD_PROCESS"))
        assertTrue(body.contains("USSD_operator=ussd_send"))
        assertTrue(body.contains("USSD_send_number=%2A310%23"))
    }

    @Test
    fun `reply posts the captured body`() = runTest {
        router.ussdFlags = listOf("16")
        router.ussdData = reply(action = "1")
        val client = router.client()

        client.ussdSend("*312#")
        client.ussdReply("1")

        val body = router.postsSeen[1]
        assertTrue(body.contains("USSD_operator=ussd_reply"))
        assertTrue(body.contains("USSD_reply_number=1"))
    }

    @Test
    fun `cancel posts cancel and does not poll`() = runTest {
        router.ussdFlags = listOf("16")

        router.client().ussdCancel()

        assertTrue(router.postsSeen.first().contains("USSD_operator=ussd_cancel"))
        assertEquals(0, router.getsFor("ussd_write_flag").size)
    }

    private companion object {
        const val UCS2_HI = "00480069"
    }
}
