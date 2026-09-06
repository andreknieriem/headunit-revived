package com.andrerinas.openheadunit.automation

import android.content.Intent

/**
 * Reads command arguments off an `Intent`, with [overrides] taking precedence. The overrides carry a
 * deep link's query parameters, which are not extras but mean the same thing, so a command reads its
 * arguments identically however it arrives.
 */
class IntentExtras(
    private val intent: Intent,
    private val overrides: Map<String, String> = emptyMap()
) : AutomationCommandPolicy.Extras {

    override fun string(key: String): String? = overrides[key] ?: intent.getStringExtra(key)

    // `am broadcast --ez` gives a real boolean and Tasker's Send Intent gives the string "true";
    // accept both rather than making the caller know which tool they are in.
    override fun flag(key: String): Boolean =
        overrides[key]?.lowercase() == "true" ||
            intent.getBooleanExtra(key, false) ||
            intent.getStringExtra(key)?.lowercase() == "true"
}
