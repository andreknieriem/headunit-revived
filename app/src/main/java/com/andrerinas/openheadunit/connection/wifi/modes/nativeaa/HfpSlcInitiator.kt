package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa

/**
 * Drives the opening of a Hands-Free service level connection, as the hands-free side.
 *
 * A phone will not start wireless Android Auto unless the head unit's Bluetooth is connected *with
 * a profile*, and a profile only reaches that state once its opening exchange finishes. This head
 * unit publishes the Hands-Free record, so per HFP the exchange is ours to start whoever opened the
 * socket; accepting the connection and saying nothing leaves the phone waiting until it times out.
 *
 * Stateful where [HfpAtResponder] is a table, because the walk has a position. One live socket needs
 * both roles at once, so this delegates the answering half rather than reimplementing it.
 *
 * Pure: strings and a stage in, strings and a stage out.
 */
object HfpSlcInitiator {

    /** How far this head unit's own half of the opening exchange has got. */
    enum class Stage { IDLE, BRSF, CIND_TEST, CIND_READ, CMER, ESTABLISHED }

    /**
     * Declares a hands-free side with no optional features. The least this can claim: no codec
     * negotiation and no call handling, which is honest, because the responder can serve neither.
     */
    const val OPENING_COMMAND = "AT+BRSF=0"
    const val INDICATOR_TEST_COMMAND = "AT+CIND=?"
    const val INDICATOR_READ_COMMAND = "AT+CIND?"
    const val EVENT_REPORTING_COMMAND = "AT+CMER=3,0,0,1"

    /** Reading the indicators changes nothing, which is the point: it is the cheapest proof the
     *  channel is still there. */
    const val KEEPALIVE_COMMAND = INDICATOR_READ_COMMAND
    const val KEEPALIVE_INTERVAL_MS = 2_000L

    /** What to write next, and where the walk stands, after one read. */
    data class Step(
        val stage: Stage,
        val writes: List<String> = emptyList(),
        val establishedNow: Boolean = false
    )

    /**
     * An AT command ends with a bare CR. Deliberately not [HfpAtResponder.frame], which wraps a
     * *response* in CRLF on both sides: a leading pair would hand the phone an empty line first, and
     * an `ERROR` answering that would advance this walk a second time.
     */
    fun command(line: String): String = "$line\r"

    /** The hands-free side speaks first, whoever opened the socket. */
    fun open(): Step = Step(Stage.BRSF, listOf(command(OPENING_COMMAND)))

    /** One received line: what it makes us send, and where it leaves the walk. */
    fun onLine(stage: Stage, line: String): Step {
        val text = line.trim()
        if (text.isEmpty()) return Step(stage)

        // The phone acting as the hands-free side against us. Not an answer to our walk, so it must
        // not move it; it is answered from the table instead.
        if (text.startsWith("AT", ignoreCase = true)) {
            val writes = HfpAtResponder.responsesFor(text).map { HfpAtResponder.frame(it) }
            // A peer that turns on event reporting has finished its own opening exchange, so the
            // link is up even though none of our commands got us there.
            val done = stage != Stage.IDLE && stage != Stage.ESTABLISHED &&
                text.startsWith("AT+CMER", ignoreCase = true)
            return Step(if (done) Stage.ESTABLISHED else stage, writes, done)
        }

        // Off, and before we have spoken, this object is exactly the old reactive responder.
        if (stage == Stage.IDLE) return Step(stage)

        val acknowledged = text.equals("OK", ignoreCase = true) ||
            text.startsWith("ERROR", ignoreCase = true)
        // An ERROR advances like an OK. A phone that refuses one optional command has still answered
        // it, and stopping the walk there would leave the link no better than never trying.
        if (!acknowledged) return Step(stage)

        return when (stage) {
            Stage.BRSF -> Step(Stage.CIND_TEST, listOf(command(INDICATOR_TEST_COMMAND)))
            Stage.CIND_TEST -> Step(Stage.CIND_READ, listOf(command(INDICATOR_READ_COMMAND)))
            Stage.CIND_READ -> Step(Stage.CMER, listOf(command(EVENT_REPORTING_COMMAND)))
            Stage.CMER -> Step(Stage.ESTABLISHED, establishedNow = true)
            // Every keepalive is answered; absorbing those here is what stops it re-establishing.
            Stage.ESTABLISHED, Stage.IDLE -> Step(stage)
        }
    }

    /**
     * Every line in one read, folded in arrival order, so a buffer carrying an answer and a question
     * sends the reply before the next command.
     */
    fun onReceived(stage: Stage, buffer: String): Step {
        var current = stage
        val writes = mutableListOf<String>()
        var established = false
        HfpAtResponder.split(buffer).forEach { line ->
            val step = onLine(current, line)
            current = step.stage
            writes += step.writes
            established = established || step.establishedNow
        }
        return Step(current, writes, established)
    }
}
