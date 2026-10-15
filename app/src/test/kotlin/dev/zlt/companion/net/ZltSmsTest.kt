package dev.zlt.companion.net

import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.reflect.KClass

/** Mirrors tests/test_client_sms.py from the Python project. */
class ZltSmsTest {

    private val router = RouterMock().apply { values["token"] = "1" }

    @After
    fun tearDown() = router.close()

    /** One message in the device's own shape. */
    private fun row(
        id: String = "656",
        tag: String = "1",
        content: String = UCS2_HI,
    ) = mapOf(
        "id" to id, "number" to "121", "content" to content,
        "tag" to tag, "date" to "26,07,24,15,28,20,+4", "draft_group_id" to "",
    )

    private suspend fun <T : Throwable> failsWith(type: KClass<T>, block: suspend () -> Unit): T {
        val error = runCatching { block() }.exceptionOrNull()
        assertTrue("expected ${type.simpleName}, got $error", type.isInstance(error))
        @Suppress("UNCHECKED_CAST")
        return error as T
    }

    // --- inbox ---------------------------------------------------------------

    @Test
    fun `a message decodes`() = runTest {
        router.smsRows = listOf(row())

        assertEquals(
            listOf(SmsMessage("656", "121", "Hi", "2026-07-24 15:28", unread = true, outgoing = false)),
            router.client().smsList(),
        )
    }

    @Test
    fun `tag 0 is read`() = runTest {
        router.smsRows = listOf(row(tag = "0"))
        assertEquals(false, router.client().smsList().single().unread)
    }

    @Test
    fun `tag 2 is outgoing and not unread`() = runTest {
        router.smsRows = listOf(row(tag = "2"))
        val message = router.client().smsList().single()
        assertEquals(true, message.outgoing)
        assertEquals(false, message.unread)
    }

    @Test
    fun `empty inbox`() = runTest {
        router.smsRows = emptyList()
        assertEquals(emptyList<SmsMessage>(), router.client().smsList())
    }

    @Test
    fun `inbox read sends the captured query`() = runTest {
        router.smsRows = emptyList()
        router.client().smsList()

        val url = router.getsFor("sms_data_total").single().requestUrl!!
        assertEquals("1", url.queryParameter("mem_store"))
        assertEquals("10", url.queryParameter("tags"))
        assertEquals("order by id desc", url.queryParameter("order_by"))
        assertEquals("0", url.queryParameter("page"))
        assertEquals(null, url.queryParameter("multi_data"))
    }

    @Test
    fun `the cap is applied to what the device returns`() = runTest {
        // data_per_page is advisory: the live device sent ten rows when asked for three.
        router.smsRows = (0 until 10).map { row(id = it.toString()) }
        assertEquals(3, router.client().smsList(limit = 3).size)
    }

    @Test
    fun `an answer with no messages list is a contract mismatch`() = runTest {
        router.smsRows = null
        failsWith(UnsupportedResponse::class) { router.client().smsList() }
    }

    // --- send ----------------------------------------------------------------

    @Test
    fun `send posts the captured body`() = runTest {
        router.client().smsSend("08012345678", "Hi")

        val body = router.postsSeen.first()
        assertTrue(body.contains("goformId=SEND_SMS"))
        assertTrue(body.contains("Number=08012345678"))
        assertTrue(body.contains("MessageBody=00480069"))
        assertTrue(body.contains("ID=-1"))
        assertTrue(body.contains("encode_type=GSM7_default"))
        assertTrue(body.contains("sms_time="))
    }

    @Test
    fun `unicode bodies are marked`() = runTest {
        router.client().smsSend("121", "coffee ☕")
        assertTrue(router.postsSeen.first().contains("encode_type=UNICODE"))
    }

    @Test
    fun `send polls its own slot`() = runTest {
        router.client().smsSend("121", "Hi")
        assertEquals("4", router.getsFor("sms_cmd_status_info").last().requestUrl!!.queryParameter("sms_cmd"))
    }

    @Test
    fun `send waits out the idle status`() = runTest {
        // The device answers {"messages": []} until the send registers.
        router.smsStatuses = listOf(null, null, "3")
        router.client().smsSend("121", "Hi")
        assertEquals(3, router.getsFor("sms_cmd_status_info").size)
    }

    @Test
    fun `network rejection fails the send`() = runTest {
        router.smsStatuses = listOf("2")
        val error = failsWith(SmsFailed::class) { router.client().smsSend("121", "Hi") }
        assertTrue(error.message!!.contains("rejected"))
    }

    @Test
    fun `router refusing the post fails the send`() = runTest {
        router.queuePost("failure")
        failsWith(SmsFailed::class) { router.client().smsSend("121", "Hi") }
        assertEquals(0, router.getsFor("sms_cmd_status_info").size)
    }

    @Test
    fun `send gives up at the deadline`() = runTest {
        router.smsStatuses = listOf(null)
        val error = failsWith(SmsFailed::class) { router.client().smsSend("121", "Hi") }
        assertTrue(error.message!!.contains("Timed out"))
        // 30s at 3s intervals: the first poll plus ten more.
        assertEquals(11, router.getsFor("sms_cmd_status_info").size)
    }

    @Test
    fun `empty recipient or body never reaches the router`() = runTest {
        failsWith(SmsFailed::class) { router.client().smsSend("  ", "Hi") }
        failsWith(SmsFailed::class) { router.client().smsSend("121", "  ") }
        assertEquals(0, router.postCount())
    }

    // --- mark read and delete ------------------------------------------------

    @Test
    fun `mark read posts the captured body`() = runTest {
        assertEquals(2, router.client().smsMarkRead(listOf("659", "658")))

        val body = router.postsSeen.first()
        assertTrue(body.contains("goformId=SET_MSG_READ"))
        assertTrue(body.contains("msg_id=659%3B658%3B"))
        assertTrue(body.contains("tag=0"))
    }

    @Test
    fun `mark read does not poll a status slot`() = runTest {
        router.client().smsMarkRead(listOf("659"))
        assertEquals(0, router.getsFor("sms_cmd_status_info").size)
    }

    @Test
    fun `mark read refused by the router fails`() = runTest {
        router.queuePost("failure")
        failsWith(SmsFailed::class) { router.client().smsMarkRead(listOf("659")) }
    }

    @Test
    fun `delete posts the captured body and polls slot six`() = runTest {
        assertEquals(2, router.client().smsDelete(listOf("659", "658")))

        val body = router.postsSeen.first()
        assertTrue(body.contains("goformId=DELETE_SMS"))
        assertTrue(body.contains("msg_id=659%3B658%3B"))
        assertEquals("6", router.getsFor("sms_cmd_status_info").last().requestUrl!!.queryParameter("sms_cmd"))
    }

    @Test
    fun `delete waits out the idle status`() = runTest {
        router.smsStatuses = listOf(null, null, "3")
        router.client().smsDelete(listOf("659"))
    }

    @Test
    fun `delete failure is reported`() = runTest {
        router.smsStatuses = listOf("2")
        failsWith(SmsFailed::class) { router.client().smsDelete(listOf("659")) }
    }

    @Test
    fun `a malformed id is refused before anything is sent`() = runTest {
        failsWith(SmsFailed::class) { router.client().smsDelete(listOf("1;2")) }
        assertEquals(0, router.requestsSeen.size)
    }

    private companion object {
        const val UCS2_HI = "00480069"
    }
}
