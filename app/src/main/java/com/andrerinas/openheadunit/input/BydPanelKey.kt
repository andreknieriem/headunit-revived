package com.andrerinas.openheadunit.input

import android.view.KeyEvent
import java.util.Locale

/**
 * Decodes the panel / steering-wheel key frames a BYD head unit broadcasts, and maps them onto the
 * key codes the rest of this app speaks.
 *
 * On these units the media buttons are **not Android `KeyEvent`s** — not merely unhandled ones, but
 * events that never exist in that form at all. The button reaches the MCU, the MCU reaches
 * `com.byd.multimediaservice` over a serial link, and that userspace service broadcasts a raw byte
 * frame. Nothing writes to `/dev/input/event*`, so `InputReader` never runs and there is no
 * `KeyEvent` for [com.andrerinas.openheadunit.connection.carkey.CarKeyBroadcastReceiver] to pick out
 * of a `MEDIA_BUTTON` intent — the stock player's own `MEDIA_BUTTON` registration on this firmware
 * points at a class that is not in its APK, because there is nothing on the other end of that path.
 * Measured directly: three confirmed key presses inside a window where an activity's
 * `dispatchKeyEvent` was logging every event produced zero dispatches.
 *
 * So the only way to see these buttons is to decode the frame, which is what this object does.
 *
 * Frame layout, from the stock player's `DZ60.dealData`:
 * ```
 *   byte 0 : group id
 *   byte 1 : sub id
 *   byte 2+: payload, meaning depends on group/sub
 * ```
 * Panel keys are group [GROUP_PANEL_KEY] / sub [SUB_KEY_DOWN] with the code in byte 2. The group/sub
 * table describes the *multimedia* channel only; the sibling channels in the same firmware reuse the
 * two-byte header with unrelated meanings (the Bluetooth one puts ASCII device names behind `02 03`),
 * which is why [ACTION] is the single action worth registering for and every other channel is left
 * alone.
 *
 * Only three codes are confirmed — they are the three the stock player implements and the two that
 * were captured live. The rest become learnable virtual key codes rather than guesses; see
 * [toKeyCode].
 *
 * Pure and unit-tested; the registration and the send live in
 * [com.andrerinas.openheadunit.connection.carkey.byd.CarBydReceiver].
 */
object BydPanelKey {

    /** The multimedia service's broadcast. Unprotected, and a custom implicit action, so a receiver
     * for it has to be registered dynamically — a manifest one is not delivered on Android 8+. */
    const val ACTION = "APP_REV_DATA"

    /** The `byte[]` extra every channel in this firmware family carries its frame in. */
    const val EXTRA_FRAME = "list"

    const val GROUP_PANEL_KEY = 4
    const val SUB_KEY_DOWN = 1

    /** Not a panel key frame. */
    const val NO_KEY = -1

    // ── Panel key codes ──────────────────────────────────────────────────────────────────────────
    // Confirmed: implemented in the stock player's `onPanelKeyDown`, and NEXT/PREV were also
    // captured live on a real unit.
    const val CODE_PLAY_PAUSE = 0x02
    const val CODE_NEXT = 0x03
    const val CODE_PREV = 0x04
    const val CODE_PHONE = 0x5E
    const val CODE_MENU = 0x5F

    /**
     * Base for the virtual key codes given to panel keys with no confirmed meaning, so a user can
     * learn them in the keymap. Follows the convention the other proprietary receivers already use
     * (NWD `1000+`, Eryanet `2001+`, BZ `3001+`) and stays clear of all of them; `CommManager`
     * drops anything at or above 1000 that the user has not mapped, which is exactly the wanted
     * behaviour for a button whose function nobody knows yet.
     */
    const val VIRTUAL_BASE = 4000

    /** One virtual code per possible payload byte. */
    val VIRTUAL_RANGE = VIRTUAL_BASE until (VIRTUAL_BASE + 256)

    /**
     * The panel key code carried by [frame], or [NO_KEY] when this frame is not a key press.
     *
     * The channel is busy with source changes, ACC state, launcher metadata and the service's own
     * echo of what other apps send it, so the group/sub check is what keeps all of that out. It also
     * means a stray broadcast of this very generic action name cannot inject an arbitrary key.
     */
    fun panelKeyCode(frame: ByteArray?): Int {
        if (frame == null || frame.size < 3) return NO_KEY
        if (u(frame[0]) != GROUP_PANEL_KEY || u(frame[1]) != SUB_KEY_DOWN) return NO_KEY
        return u(frame[2])
    }

    /**
     * The key code to hand to `CarKeyReceiver.handleClick` for a panel key code.
     *
     * The three confirmed codes become the real Android media key codes, so play/pause, next and
     * previous work on a BYD unit with nothing configured. Everything else becomes a virtual code:
     * sending a guess would be worse than sending nothing, because a wrong guess is a button that
     * does the wrong thing every time and reads as a bug, whereas a virtual code shows up in the
     * keymap screen ready to be assigned to whichever Android Auto action the user finds it should
     * do.
     */
    fun toKeyCode(panelCode: Int): Int = when (panelCode) {
        CODE_PLAY_PAUSE -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
        CODE_NEXT -> KeyEvent.KEYCODE_MEDIA_NEXT
        CODE_PREV -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
        else -> VIRTUAL_BASE + panelCode
    }
    /** The panel key code behind a virtual key code, or null when [keyCode] is not one of ours. */
    fun panelCodeOf(keyCode: Int): Int? =
        if (keyCode in VIRTUAL_RANGE) keyCode - VIRTUAL_BASE else null

    /**
     * A name for one of our virtual key codes, for the keymap screen — `KeyEvent.keyCodeToString`
     * renders these as a bare number, which tells a user nothing about which button they just
     * pressed. Null for any key code that is not ours.
     *
     * Deliberately not translated, like the `keyCodeToString` names it appears alongside: this
     * names a piece of hardware, and the hex is what a user quotes in a bug report. The question
     * mark on the phone button is not decoration — that reading is an inference from correlated
     * Bluetooth traffic, never confirmed at the unit.
     */
    fun label(keyCode: Int): String? {
        val panelCode = panelCodeOf(keyCode) ?: return null
        val name = when (panelCode) {
            CODE_PHONE -> "PHONE"
            CODE_MENU -> "MENU"
            else -> "KEY"
        }
        return String.format(Locale.US, "BYD %s (0x%02X)", name, panelCode)
    }

    /** Hex rendering of a frame, so an unrecognised one stays recoverable from a user's log. */
    fun hex(frame: ByteArray): String =
        frame.joinToString(" ") { String.format(Locale.US, "%02X", it.toInt() and 0xFF) }

    private fun u(b: Byte): Int = b.toInt() and 0xFF
}
