package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa

/** What the wake poke does when it looks at its target list. */
sealed class PokeTargets {
    /** Poke these addresses, in the order the caller resolves them. */
    data class Selected(val macs: Set<String>) : PokeTargets()

    /** Poke every paired device: the opt-in is on and nothing specific was chosen. */
    object AllPaired : PokeTargets()

    /** Poke nothing, because no target is set and widening was not asked for. */
    object None : PokeTargets()
}

/**
 * Who the Native AA wake poke connects to.
 *
 * An empty list used to mean "every paired device", which is how a cleared setting adopted the next
 * phone that answered and wrote itself back. Widening is opt-in now, so empty means empty.
 */
object PokeTargetPolicy {

    fun targets(selected: Set<String>, allPairedOptIn: Boolean): PokeTargets = when {
        selected.isNotEmpty() -> PokeTargets.Selected(selected)
        allPairedOptIn -> PokeTargets.AllPaired
        else -> PokeTargets.None
    }

    /**
     * Whether a device that just completed a handshake may be remembered as the target.
     *
     * Only when there is no target to overwrite. Adopting a phone over a choice the user made is
     * what made a removal undo itself.
     */
    fun adoptsHandshakedDevice(selected: Set<String>): Boolean = selected.isEmpty()
}
