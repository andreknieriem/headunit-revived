package com.andrerinas.openheadunit.aap.protocol.messages

/**
 * Whether service discovery announces the media and speech audio sinks.
 *
 * System sounds are always announced; these two are the ones a setting and Self Mode can drop. The
 * Self Mode half must be a fact about *this* session: it used to read a launcher flag that outlived
 * a failed launch, so a wireless session announced system sounds only and had no audio at all.
 */
object AudioSinkAnnouncementPolicy {

    /**
     * Why Self Mode drops them at all is not recorded anywhere in this repo and is not settled;
     * only the gating is. A session that is not Self Mode's must never be affected by it.
     */
    fun announcesMediaAndSpeech(sinkEnabled: Boolean, isSelfModeSession: Boolean): Boolean =
        sinkEnabled && !isSelfModeSession
}
