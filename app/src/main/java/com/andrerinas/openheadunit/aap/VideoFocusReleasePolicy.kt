package com.andrerinas.openheadunit.aap

/**
 * Whether losing the projection surface should tell the phone we gave up video focus.
 *
 * The framework destroys a SurfaceView's surface whenever an opaque window covers the app, and the
 * teardown has always answered with VIDEO_FOCUS_NATIVE. Android Auto reads that as the projection
 * being over and tears the session down, which takes its phone keyboard with it: the keyboard opens
 * over the projection when a text field is tapped, our release goes out, and Gearhead detaches it
 * tens of milliseconds later. The user is left with no keyboard at all. The TextureView and GLES
 * backends never lose their surface to a cover, never send the release, and keep the keyboard - the
 * difference two reporters found by switching backends.
 *
 * The release is not removable in general. It is what makes the phone re-run sink setup, and the
 * picture comes back in 42-96 ms with it against seconds without. So it is withheld only for the one
 * cover shaped like the keyboard - opaque, on a live session, arriving within [TOUCH_WINDOW_MS] of a
 * touch we forwarded - and the return is left to the warm-relaunch focus cycle, measured at
 * 3.04-3.20 s. Everything else keeps the fast path.
 *
 * Withheld, never deferred. A release posted to fire later can land after a keyframe cycle has
 * completed, leaving the phone released with nothing pending to take focus back - a black screen
 * with audio still playing.
 */
object VideoFocusReleasePolicy {

    /**
     * How long after a touch of ours a cover still counts as that touch's doing.
     *
     * Android Auto's phone keyboard opens a fraction of a second after the field is tapped. An
     * alarm, a notification-launched app or a Home press arrives with no forwarded touch behind it.
     */
    const val TOUCH_WINDOW_MS = 3_000L

    /** Whether a cover this close behind a touch of ours is that touch's doing. */
    fun coverFollowsTouch(lastTouchAtMs: Long, coveredAtMs: Long): Boolean =
        lastTouchAtMs > 0L && coveredAtMs - lastTouchAtMs <= TOUCH_WINDOW_MS

    /**
     * @param coverFollowsTouch see [coverFollowsTouch]. The only signal that separates the keyboard
     *   from every other coverer: its identity cannot be read without permissions, and
     *   onUserLeaveHint fires for it exactly as for a Home press.
     * @param sessionConnected whether there is a live session whose keyboard is worth protecting.
     * @param activityEnding whether the projection activity is finishing or being recreated, in
     *   which case the surface is not coming back and the phone should be told.
     * @param pipActive whether picture-in-picture owns the screen, where being covered is the point.
     * @param focusCycleInFlight whether a keyframe cycle already has focus released. Withholding
     *   here would change nothing and the cycle owns the regain.
     */
    fun shouldReleaseOnSurfaceLost(
        coverFollowsTouch: Boolean,
        sessionConnected: Boolean,
        activityEnding: Boolean,
        pipActive: Boolean,
        focusCycleInFlight: Boolean,
    ): Boolean {
        if (!coverFollowsTouch) return true
        if (!sessionConnected || activityEnding || pipActive || focusCycleInFlight) return true
        return false
    }
}
