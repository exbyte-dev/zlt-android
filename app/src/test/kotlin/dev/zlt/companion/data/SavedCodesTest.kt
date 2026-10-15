package dev.zlt.companion.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** Mirrors tests/test_ussd_store.py: the same rules, the same file format. */
class SavedCodesTest {

    @Test
    fun `round trips`() {
        val codes = listOf(SavedCode("Balance", "*310#"), SavedCode("Data", "*323#"))
        assertEquals(codes, SavedCodes.parse(SavedCodes.serialize(codes)))
    }

    @Test
    fun `reads the python store's own format`() {
        val raw = """[{"label": "Balance", "code": "*310#"}, {"label": "Data", "code": "*323#"}]"""
        assertEquals(
            listOf(SavedCode("Balance", "*310#"), SavedCode("Data", "*323#")),
            SavedCodes.parse(raw),
        )
    }

    @Test
    fun `junk reads as empty rather than crashing`() {
        assertEquals(emptyList<SavedCode>(), SavedCodes.parse(""))
        assertEquals(emptyList<SavedCode>(), SavedCodes.parse("not json"))
        assertEquals(emptyList<SavedCode>(), SavedCodes.parse("""{"label": "x"}"""))
    }

    @Test
    fun `entries missing a field are skipped`() {
        val raw = """[{"label": "Balance"}, {"label": "Data", "code": "*323#"}, 5]"""
        assertEquals(listOf(SavedCode("Data", "*323#")), SavedCodes.parse(raw))
    }

    @Test
    fun `saving an existing label replaces it, ignoring case`() {
        val codes = listOf(SavedCode("Balance", "*310#"))
        assertEquals(listOf(SavedCode("balance", "*311#")), SavedCodes.upsert(codes, " balance ", " *311# "))
    }

    @Test
    fun `saving a new label appends`() {
        val codes = listOf(SavedCode("Balance", "*310#"))
        assertEquals(
            listOf(SavedCode("Balance", "*310#"), SavedCode("Data", "*323#")),
            SavedCodes.upsert(codes, "Data", "*323#"),
        )
    }

    @Test
    fun `remove ignores case`() {
        val codes = listOf(SavedCode("Balance", "*310#"), SavedCode("Data", "*323#"))
        assertEquals(listOf(SavedCode("Data", "*323#")), SavedCodes.remove(codes, "BALANCE"))
    }
}
