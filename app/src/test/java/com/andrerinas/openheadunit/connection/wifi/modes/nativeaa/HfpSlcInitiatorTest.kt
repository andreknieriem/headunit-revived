package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa

import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.HfpSlcInitiator.Stage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HfpSlcInitiatorTest {

    private fun ok(stage: Stage) = HfpSlcInitiator.onLine(stage, "OK")

    @Test
    fun `the hands-free side opens with AT+BRSF and nothing else`() {
        val step = HfpSlcInitiator.open()
        assertEquals(Stage.BRSF, step.stage)
        assertEquals(listOf("AT+BRSF=0\r"), step.writes)
        assertFalse(step.establishedNow)
    }

    @Test
    fun `a command ends with a bare CR and carries no leading blank line`() {
        // A response is wrapped in CRLF on both sides; a command is not. Framing a command that way
        // puts an empty line in front of it, and an ERROR answering that would advance the walk a
        // second time and skip a step.
        assertEquals("AT+CIND?\r", HfpSlcInitiator.command("AT+CIND?"))
        assertNotEquals(HfpAtResponder.frame("AT+CIND?"), HfpSlcInitiator.command("AT+CIND?"))
    }

    @Test
    fun `each acknowledgement sends the next command in the walk`() {
        val test = ok(Stage.BRSF)
        assertEquals(Stage.CIND_TEST, test.stage)
        assertEquals(listOf("AT+CIND=?\r"), test.writes)

        val read = ok(test.stage)
        assertEquals(Stage.CIND_READ, read.stage)
        assertEquals(listOf("AT+CIND?\r"), read.writes)

        val reporting = ok(read.stage)
        assertEquals(Stage.CMER, reporting.stage)
        assertEquals(listOf("AT+CMER=3,0,0,1\r"), reporting.writes)
    }

    @Test
    fun `the fourth acknowledgement establishes the link and sends nothing more`() {
        val step = ok(Stage.CMER)
        assertEquals(Stage.ESTABLISHED, step.stage)
        assertTrue(step.establishedNow)
        assertTrue(step.writes.isEmpty())
    }

    @Test
    fun `an ERROR advances the walk exactly as an acknowledgement does`() {
        // A phone that refuses one optional command has still answered it. Stopping there would
        // leave the link no better than never having tried.
        assertEquals(Stage.CIND_TEST, HfpSlcInitiator.onLine(Stage.BRSF, "ERROR").stage)
        assertEquals(Stage.CMER, HfpSlcInitiator.onLine(Stage.CIND_READ, "ERROR").stage)
        assertTrue(HfpSlcInitiator.onLine(Stage.CMER, "ERROR").establishedNow)
    }

    @Test
    fun `acknowledgements are read whatever their case`() {
        assertEquals(Stage.CIND_TEST, HfpSlcInitiator.onLine(Stage.BRSF, "ok").stage)
        assertEquals(Stage.CIND_TEST, HfpSlcInitiator.onLine(Stage.BRSF, "Ok").stage)
        assertEquals(Stage.CIND_TEST, HfpSlcInitiator.onLine(Stage.BRSF, "error").stage)
    }

    @Test
    fun `nothing more is sent once the link is established`() {
        // The keepalive's own answers arrive here forever; absorbing them is what stops the walk
        // re-establishing and launching a second keepalive.
        val first = ok(Stage.ESTABLISHED)
        val second = ok(first.stage)
        listOf(first, second).forEach {
            assertEquals(Stage.ESTABLISHED, it.stage)
            assertTrue(it.writes.isEmpty())
            assertFalse(it.establishedNow)
        }
    }

    @Test
    fun `an unsolicited result never advances the walk`() {
        listOf("+BRSF: 20", "+CIND: 1,0,0,0,5,0,5", "+CIEV: 2,1", "RING", "BUSY", "").forEach {
            val step = HfpSlcInitiator.onLine(Stage.CIND_TEST, it)
            assertEquals("'$it' must not advance the walk", Stage.CIND_TEST, step.stage)
            assertTrue("'$it' must send nothing", step.writes.isEmpty())
        }
    }

    @Test
    fun `a command from the phone is answered without moving the walk`() {
        val step = HfpSlcInitiator.onLine(Stage.CIND_TEST, "AT+CIND=?")
        assertEquals(Stage.CIND_TEST, step.stage)
        assertEquals(HfpAtResponder.responsesFor("AT+CIND=?").map { HfpAtResponder.frame(it) }, step.writes)
    }

    @Test
    fun `a phone that turns on event reporting counts as established`() {
        val step = HfpSlcInitiator.onLine(Stage.CIND_READ, "AT+CMER=3,0,0,1")
        assertEquals(Stage.ESTABLISHED, step.stage)
        assertTrue(step.establishedNow)
        // It is still answered, or the phone's own walk stalls where ours used to.
        assertTrue(step.writes.isNotEmpty())
        // Already up, so it is not established a second time and cannot arm a second keepalive.
        assertFalse(HfpSlcInitiator.onLine(Stage.ESTABLISHED, "AT+CMER=3,0,0,1").establishedNow)
    }

    @Test
    fun `every stage still answers the phone`() {
        Stage.entries.forEach { stage ->
            assertTrue(
                "stage $stage must still answer AT+CGMI",
                HfpSlcInitiator.onLine(stage, "AT+CGMI").writes.isNotEmpty()
            )
        }
    }

    @Test
    fun `at IDLE this is exactly the old reactive responder`() {
        // The contract when the setting is off: nothing is ever sent unprompted, no stage is
        // entered, and so no keepalive can start.
        listOf("OK", "ERROR", "+CIND: 1", "AT+CMER=3,0,0,1", "AT+BRSF=20").forEach { line ->
            val step = HfpSlcInitiator.onLine(Stage.IDLE, line)
            assertEquals("'$line' must not move IDLE", Stage.IDLE, step.stage)
            assertFalse("'$line' must not establish from IDLE", step.establishedNow)
            assertEquals("'$line' must answer as the table does", HfpAtResponder.replyTo(line), step.writes)
        }
    }

    @Test
    fun `one read carrying several lines is folded in order`() {
        val step = HfpSlcInitiator.onReceived(Stage.BRSF, "\r\nOK\r\n\r\nOK\r\n")
        assertEquals(Stage.CIND_READ, step.stage)
        assertEquals(listOf("AT+CIND=?\r", "AT+CIND?\r"), step.writes)
    }

    @Test
    fun `a read mixing a question and an answer replies before it advances`() {
        val step = HfpSlcInitiator.onReceived(Stage.BRSF, "AT+CGMI\rOK\r")
        assertEquals(Stage.CIND_TEST, step.stage)
        assertEquals(HfpAtResponder.replyTo("AT+CGMI") + "AT+CIND=?\r", step.writes)
    }

    @Test
    fun `the keepalive reads the indicators every two seconds`() {
        assertEquals("AT+CIND?", HfpSlcInitiator.KEEPALIVE_COMMAND)
        assertEquals(2_000L, HfpSlcInitiator.KEEPALIVE_INTERVAL_MS)
    }
}
