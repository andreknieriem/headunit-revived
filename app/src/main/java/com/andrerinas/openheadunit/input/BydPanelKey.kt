package com.andrerinas.openheadunit.input

import java.util.Locale

/**
 * Decodes the panel / steering-wheel key frames a BYD head unit broadcasts, and maps them onto the
 * key codes the rest of this app speaks.
 * Information onf how steering wheel keys are handled decoded by reverse engineering BYD system apps
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
     * The catalogue, for logs and for the keymap screen. Twenty panel buttons from the factory test
     * app plus the three steering-wheel codes.
     *
     * `Next` appears twice on purpose — `0x03` on the wheel, `0x84` on the panel — as does the pair
     * `Prev`/`Pre`. They are separate buttons in separate ranges that happen to do the same thing.
     */
    private val NAMES = mapOf(
        0x02 to "PlayPause",
        0x03 to "Next",
        0x04 to "Prev",
        0x10 to "VolumeDown",
        0x11 to "VolumeUp",
        0x43 to "TurnUp",
        0x44 to "TurnDown",
        0x45 to "Aux",
        0x53 to "Phone",
        0x54 to "SD",
        0x5D to "Voice",
        0x5E to "Connect",
        0x5F to "Drop",
        0x7E to "USB",
        0x81 to "Mode",
        0x82 to "Pre",
        0x84 to "Next",
        0x86 to "RightFront",
        0x88 to "Power",
        0x8D to "Radio",
        0x9C to "Music",
        0x9D to "Video",
        0x9E to "ScreenOff",
    )

    /**
     * The panel key code carried by [frame], or [NO_KEY] when this frame is not a key press.
     *
     * The channel is busy with source changes, ACC state, camera control, launcher metadata and the
     * service's own echo of what other apps send it, so the group/sub check is what keeps all of that
     * out.
     */
    fun panelKeyCode(frame: ByteArray?): Int {
        if (frame == null || frame.size < 3) return NO_KEY
        if (u(frame[0]) != GROUP_PANEL_KEY || u(frame[1]) != SUB_KEY_DOWN) return NO_KEY
        return u(frame[2])
    }

    /**
     * The key code to hand to `CarKeyReceiver.handleClick` for a panel key code.
     *
     * **Every** panel key becomes a virtual code — none is mapped to an Android key code here, not
     * even the three the stock player implements. Nothing this channel produces reaches Android Auto
     * until the user binds it in the keymap screen.
     *
     * `CommManager.sendKey` drops an unmapped code at or above 1000 rather than sending nonsense to
     * the phone, so an unbound button is inert rather than unpredictable. [nameOf] is what makes
     * binding it practical: the keymap screen shows the button by name instead of a bare number.
     */
    fun toKeyCode(panelCode: Int): Int = VIRTUAL_BASE + panelCode

    /** The panel key code behind a virtual key code, or null when [keyCode] is not one of ours. */
    fun panelCodeOf(keyCode: Int): Int? =
        if (keyCode in VIRTUAL_RANGE) keyCode - VIRTUAL_BASE else null

    /** The catalogued name of a panel code, or null for one nobody has identified. */
    fun nameOf(panelCode: Int): String? = NAMES[panelCode]

    /**
     * A name for one of our virtual key codes, for the keymap screen — `KeyEvent.keyCodeToString`
     * renders these as a bare number, which tells a user nothing about which button they just
     * pressed. Null for any key code that is not ours, including the panel codes that map to real
     * Android key codes and so already have a platform name.
     *
     * Deliberately not translated, like the `keyCodeToString` names it appears alongside: this names
     * a piece of hardware, and the hex is what a user quotes in a bug report.
     */
    fun label(keyCode: Int): String? {
        val panelCode = panelCodeOf(keyCode) ?: return null
        return String.format(Locale.US, "BYD %s (0x%02X)", nameOf(panelCode) ?: "key", panelCode)
    }

    /** Hex rendering of a frame, so an unrecognised one stays recoverable from a user's log. */
    fun hex(frame: ByteArray): String =
        frame.joinToString(" ") { String.format(Locale.US, "%02X", it.toInt() and 0xFF) }

    private fun u(b: Byte): Int = b.toInt() and 0xFF
}
