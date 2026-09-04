package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HfpAtResponderTest {

    private fun body(command: String) = HfpAtResponder.responsesFor(command)

    @Test
    fun `every answered command ends in OK`() {
        listOf(
            "AT+BRSF=20", "AT+CIND=?", "AT+CIND?", "AT+CHLD=?", "AT+BIND=?", "AT+BIND?",
            "AT+CGMI", "AT+CGMM", "AT+CGMR", "AT+CMER=3,0,0,1", "AT+SOMETHINGUNKNOWN"
        ).forEach { assertEquals(it, "OK", body(it).last()) }
    }

    @Test
    fun `the feature bitmap and the call-hold list are answered`() {
        assertEquals(listOf("+BRSF: 20", "OK"), body("AT+BRSF=20"))
        assertEquals(listOf("+CHLD: (0,1,2,3)", "OK"), body("AT+CHLD=?"))
    }

    @Test
    fun `the indicator list and its values agree on count and order`() {
        val declared = body("AT+CIND=?").first()
        val reported = body("AT+CIND?").first()
        val names = Regex("\"([a-z]+)\"").findAll(declared).map { it.groupValues[1] }.toList()
        val values = reported.removePrefix("+CIND: ").split(",")
        assertEquals(
            listOf("service", "call", "callsetup", "callheld", "signal", "roam", "battchg"),
            names
        )
        // A phone reads the values positionally, so a mismatch here misreports every indicator.
        assertEquals(names.size, values.size)
    }

    @Test
    fun `the indicator values are in range for their declared bounds`() {
        assertEquals(listOf("1", "0", "0", "0", "5", "0", "5"),
            body("AT+CIND?").first().removePrefix("+CIND: ").split(","))
    }

    @Test
    fun `both indicator activation lines are sent`() {
        assertEquals(listOf("+BIND: (1,2)", "OK"), body("AT+BIND=?"))
        assertEquals(listOf("+BIND: 1,1", "+BIND: 2,1", "OK"), body("AT+BIND?"))
    }

    @Test
    fun `the more specific CIND test form wins over the read form`() {
        // "AT+CIND=?" also starts with "AT+CIND", so the order of the branches is load-bearing.
        assertTrue(body("AT+CIND=?").first().contains("(\"service\""))
        assertTrue(body("AT+CIND?").first().matches(Regex("""\+CIND: [\d,]+""")))
    }

    @Test
    fun `device identification is answered`() {
        assertEquals(listOf("""+CGMI: "Open Headunit"""", "OK"), body("AT+CGMI"))
        assertEquals(listOf("""+CGMM: "Open Headunit"""", "OK"), body("AT+CGMM"))
        assertEquals(listOf("""+CGMR: "1.0"""", "OK"), body("AT+CGMR"))
    }

    @Test
    fun `an unrecognised AT command is still acknowledged`() {
        assertEquals(listOf("OK"), body("AT+CMER=3,0,0,1"))
    }

    @Test
    fun `anything that is not an AT command draws no reply at all`() {
        // An unsolicited OK on a stray byte can desynchronise the phone's SLC state machine.
        listOf("", "   ", "OK", "garbage", "+CIND: 1,0").forEach {
            assertEquals(it, emptyList<String>(), body(it))
        }
    }

    @Test
    fun `several commands in one read are each answered`() {
        val replies = HfpAtResponder.replyTo("AT+BRSF=20\r\nAT+CIND=?\rAT+CHLD=?\r")
        assertEquals(6, replies.size)
        assertTrue(replies[0].contains("+BRSF: 20"))
        assertTrue(replies[2].contains("\"service\""))
        assertTrue(replies[4].contains("+CHLD: (0,1,2,3)"))
    }

    @Test
    fun `splitting drops the blanks that CRLF framing leaves behind`() {
        assertEquals(listOf("AT+BRSF=20", "AT+CIND?"), HfpAtResponder.split("\r\nAT+BRSF=20\r\n\r\nAT+CIND?\r\n"))
        assertEquals(emptyList<String>(), HfpAtResponder.split("\r\n\r\n"))
    }

    @Test
    fun `a reply is framed with CRLF on both sides`() {
        assertEquals("\r\nOK\r\n", HfpAtResponder.frame("OK"))
        assertTrue(HfpAtResponder.replyTo("AT+BRSF=20\r").all { it.startsWith("\r\n") && it.endsWith("\r\n") })
    }

    @Test
    fun `a read with nothing usable in it produces no writes`() {
        assertEquals(emptyList<String>(), HfpAtResponder.replyTo("\r\n\r\n"))
        assertEquals(emptyList<String>(), HfpAtResponder.replyTo("noise"))
    }
}
