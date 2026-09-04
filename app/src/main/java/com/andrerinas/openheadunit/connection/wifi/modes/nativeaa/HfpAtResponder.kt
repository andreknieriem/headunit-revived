package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa

/**
 * The AT commands this head unit answers on its stand-in Hands-Free channel, and what it answers.
 *
 * Enough of the service-level connection for a phone to accept the link while Android Auto starts;
 * it carries no call audio, negotiating neither a codec nor a SCO link. A table rather than a chain
 * of ifs so the replies can be tested, which is what caught the three defects below.
 *
 * Pure: strings in, strings out.
 */
object HfpAtResponder {

    /** The seven standard indicators, in the order [CIND_VALUES] reports them. */
    private const val CIND_TEST =
        """+CIND: ("service",(0,1)),("call",(0,1)),("callsetup",(0,3)),("callheld",(0,2)),""" +
            """("signal",(0,5)),("roam",(0,1)),("battchg",(0,5))"""

    /** Registered, idle, full signal, not roaming, full battery. Must match [CIND_TEST]'s order. */
    private const val CIND_VALUES = "+CIND: 1,0,0,0,5,0,5"

    /**
     * One read can carry several commands. The previous responder matched the whole buffer at once
     * and so answered only the first, leaving the phone waiting on the rest.
     */
    fun split(buffer: String): List<String> =
        buffer.split('\r', '\n').map { it.trim() }.filter { it.isNotEmpty() }

    /** The payload lines for [command], empty for anything that is not an AT command. */
    fun responsesFor(command: String): List<String> {
        val cmd = command.trim()
        // Answering anything that is not an AT command, as the previous responder did, puts an
        // unsolicited OK on a stray byte and can desynchronise the phone's own state machine.
        if (!cmd.startsWith("AT")) return emptyList()
        val body = when {
            cmd.startsWith("AT+BRSF") -> listOf("+BRSF: 20")
            cmd.startsWith("AT+CIND=?") -> listOf(CIND_TEST)
            cmd.startsWith("AT+CIND?") -> listOf(CIND_VALUES)
            cmd.startsWith("AT+CHLD=?") -> listOf("+CHLD: (0,1,2,3)")
            cmd.startsWith("AT+BIND=?") -> listOf("+BIND: (1,2)")
            cmd.startsWith("AT+BIND?") -> listOf("+BIND: 1,1", "+BIND: 2,1")
            cmd.startsWith("AT+CGMI") -> listOf("""+CGMI: "Open Headunit"""")
            cmd.startsWith("AT+CGMM") -> listOf("""+CGMM: "Open Headunit"""")
            cmd.startsWith("AT+CGMR") -> listOf("""+CGMR: "1.0"""")
            else -> emptyList()
        }
        return body + "OK"
    }

    /** HFP frames a response as CRLF, the line, CRLF. The previous responder omitted the leading pair. */
    fun frame(line: String): String = "\r\n$line\r\n"

    /** Every reply to [buffer], already framed, ready to write in order. */
    fun replyTo(buffer: String): List<String> =
        split(buffer).flatMap { responsesFor(it) }.map { frame(it) }
}
