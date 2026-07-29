package dev.zlt.companion.net

import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mirrors tests/test_client_auth.py and tests/test_client_reads.py from the
 * Python project, assertion for assertion where the behaviour is shared.
 */
class ZltClientTest {

    private val router = RouterMock()

    @After
    fun tearDown() = router.close()

    // --- reads ---------------------------------------------------------------

    @Test
    fun `single cmd does not send multi_data`() = runTest {
        router.values["network_type"] = "LTE"
        val data = router.client().get("network_type")

        assertEquals(mapOf("network_type" to "LTE"), data)
        val path = router.requestsSeen.first().path!!
        assertTrue(path.contains("isTest=false"))
        assertTrue("multi_data must be absent for a single cmd", !path.contains("multi_data"))
    }

    @Test
    fun `multiple cmds send multi_data`() = runTest {
        router.values += mapOf("rssi" to "-90", "signalbar" to "5")
        val data = router.client().get("rssi", "signalbar")

        assertEquals(mapOf("rssi" to "-90", "signalbar" to "5"), data)
        assertTrue(router.requestsSeen.first().path!!.contains("multi_data=1"))
    }

    @Test
    fun `cmd separator goes out percent encoded`() = runTest {
        // The Python's requests encodes the comma, and so does the device's own
        // web UI via encodeURIComponent. Matching that is the whole reason the
        // query string is built by hand rather than with addQueryParameter.
        router.client().get("rssi", "signalbar")

        val path = router.requestsSeen.first().path!!
        assertTrue("expected %2C in $path", path.contains("cmd=rssi%2Csignalbar"))
    }

    @Test
    fun `device headers are replicated`() = runTest {
        router.client().get("network_type")

        val request = router.requestsSeen.first()
        assertEquals("XMLHttpRequest", request.getHeader("X-Requested-With"))
        assertEquals("http://${router.host}/index.html", request.getHeader("Referer"))
    }

    @Test
    fun `token reads either key spelling`() = runTest {
        router.values["token"] = "2315231"
        assertEquals("2315231", router.client().token())
    }

    @Test
    fun `token is empty when get_token key is empty`() = runTest {
        router.values["get_token"] = ""
        assertEquals("", router.client().token())
    }

    @Test
    fun `attempts remaining defaults to five when device says nothing`() = runTest {
        router.values += mapOf("psw_fail_num_str" to "", "login_lock_time" to "")
        val (remaining, lock) = router.client().attemptsRemaining()

        assertEquals(5, remaining)
        assertEquals(300, lock)
    }

    @Test
    fun `unparseable attempt count is treated as zero`() = runTest {
        // Errs toward refusing to log in rather than toward trying.
        router.values += mapOf("psw_fail_num_str" to "not a number", "login_lock_time" to "")
        assertEquals(0, router.client().attemptsRemaining().first)
    }

    @Test
    fun `non json response becomes RouterUnreachable`() = runTest {
        router.answerRawGet("<html>not json</html>")

        val error = runCatching { router.client().get("network_type") }.exceptionOrNull()
        assertTrue("got $error", error is RouterUnreachable)
    }

    // --- login ---------------------------------------------------------------

    @Test
    fun `login sends the encoded credentials and keeps the session cookie`() = runTest {
        router.values += mapOf(
            "psw_fail_num_str" to "5",
            "login_lock_time" to "300",
            "random_login" to "12345678",
            "get_token" to "",
        )
        router.queuePost("0")
        router.setsCookieOnLogin("abc123")

        val client = router.client()
        assertEquals("0", client.login()["result"])

        val body = router.postsSeen.first()
        assertTrue(body.contains("goformId=LOGIN"))
        assertTrue(body.contains("username=YWRtaW4%3D"))            // Base64("admin"), url-encoded
        assertTrue(body.contains("password=OTZlNmM5"))              // start of the pinned vector
        assertTrue(body.contains("isTest=false"))

        // The cookie the device set must ride along on the next request.
        router.values["network_type"] = "LTE"
        client.get("network_type")
        val cookie = router.requestsSeen.last().getHeader("Cookie")
        assertTrue("expected the random cookie, got $cookie", cookie!!.contains("random=abc123"))
    }

    @Test
    fun `login accepts result 4 as already logged in`() = runTest {
        router.values += mapOf(
            "psw_fail_num_str" to "5", "random_login" to "12345678", "get_token" to "",
        )
        router.queuePost("4")

        assertEquals("4", router.client().login()["result"])
    }

    @Test
    fun `login refuses to try when too few attempts remain`() = runTest {
        router.values += mapOf("psw_fail_num_str" to "1", "login_lock_time" to "300")

        val error = runCatching { router.client().login() }.exceptionOrNull()
        assertTrue("got $error", error is LockedOut)
        assertEquals(1, (error as LockedOut).attemptsRemaining)
        // The critical assertion: no POST was attempted at all.
        assertEquals(0, router.postCount())
    }

    @Test
    fun `login with two attempts left is still allowed`() = runTest {
        // The boundary: the guard trips below two, not at two.
        router.values += mapOf(
            "psw_fail_num_str" to "2", "random_login" to "12345678", "get_token" to "",
        )
        router.queuePost("0")

        assertEquals("0", router.client().login()["result"])
    }

    @Test
    fun `wrong password raises LoginFailed`() = runTest {
        router.values += mapOf(
            "psw_fail_num_str" to "5", "random_login" to "12345678", "get_token" to "",
        )
        router.queuePost("3")

        val error = runCatching { router.client().login() }.exceptionOrNull()
        assertTrue("got $error", error is LoginFailed)
    }

    @Test
    fun `login without a password never touches the network`() = runTest {
        val error = runCatching { router.client(password = "").login() }.exceptionOrNull()

        assertTrue("got $error", error is LoginFailed)
        assertEquals(0, router.requestsSeen.size)
    }

    @Test
    fun `missing nonce raises LoginFailed`() = runTest {
        router.values += mapOf("psw_fail_num_str" to "5", "random_login" to "")

        val error = runCatching { router.client().login() }.exceptionOrNull()
        assertTrue("got $error", error is LoginFailed)
        assertEquals(0, router.postCount())
    }

    // --- writes --------------------------------------------------------------

    @Test
    fun `ensureSession skips login when a token is already present`() = runTest {
        router.values["token"] = "2315231"
        router.client().ensureSession()

        assertEquals(0, router.postCount())
    }

    @Test
    fun `post injects the token and isTest`() = runTest {
        router.values["token"] = "999"
        router.queuePost("success")

        val data = router.client().post(
            ZltProtocol.GOFORM_SET_BEARER,
            mapOf("BearerPreference" to "NETWORK_auto"),
        )

        assertEquals("success", data["result"])
        val body = router.postsSeen.first()
        assertTrue(body.contains("goformId=SET_BEARER_PREFERENCE"))
        assertTrue(body.contains("BearerPreference=NETWORK_auto"))
        assertTrue(body.contains("CSRFToken=999"))
        assertTrue(body.contains("isTest=false"))
    }

    @Test
    fun `post re-logs-in once and retries on an auth failure marker`() = runTest {
        router.values += mapOf(
            "token" to "5", "psw_fail_num_str" to "5", "random_login" to "12345678",
        )
        router.queuePost("no_session")   // the write is refused
        router.queuePost("0")            // the re-login
        router.queuePost("success")      // the retried write

        val data = router.client().post(
            ZltProtocol.GOFORM_SET_BEARER,
            mapOf("BearerPreference" to "Only_LTE"),
        )

        assertEquals("success", data["result"])
        assertEquals(3, router.postCount())
        assertTrue(router.postsSeen[1].contains("goformId=LOGIN"))
    }

    @Test
    fun `post gives up after one retry`() = runTest {
        router.values += mapOf(
            "token" to "5", "psw_fail_num_str" to "5", "random_login" to "12345678",
        )
        router.queuePost("no_session")
        router.queuePost("0")
        router.queuePost("no_session")   // still refused after re-login

        val data = router.client().post(ZltProtocol.GOFORM_SET_BEARER, mapOf("x" to "y"))

        // Handed back as-is rather than looping. Three POSTs, not more.
        assertEquals("no_session", data["result"])
        assertEquals(3, router.postCount())
    }

    @Test
    fun `post body is form urlencoded with the charset the device expects`() = runTest {
        router.values["token"] = "1"
        router.queuePost("success")

        router.client().post("SOME_ACTION", mapOf("value" to "a+b/c=d"))

        val request = router.requestsSeen.last()
        assertEquals(
            "application/x-www-form-urlencoded; charset=UTF-8",
            request.getHeader("Content-Type"),
        )
        // The reserved characters that appear in Base64 must survive encoding.
        assertTrue(router.postsSeen.first().contains("value=a%2Bb%2Fc%3Dd"))
    }

    @Test
    fun `forgetSession drops the cookie`() = runTest {
        router.values += mapOf(
            "psw_fail_num_str" to "5", "random_login" to "12345678", "get_token" to "",
        )
        router.queuePost("0")
        router.setsCookieOnLogin("abc123")

        val client = router.client()
        client.login()
        client.forgetSession()
        client.get("network_type")

        assertNull(router.requestsSeen.last().getHeader("Cookie"))
    }
}
