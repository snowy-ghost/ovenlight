package com.snowyghost.ovenlight

/** The owner's People & Sharing. */
object SharingList {
    /**
     * How many of the tailnet's tagged devices, which Tailscale's plans count, the owner's
     * shareable apps and guests' devices take. Null before a connector has counted them.
     */
    fun taggedDevices(machines: List<OwnerSharing.Machine>): String? {
        val counts = machines.mapNotNull { it.guests.taggedDevices }
        val all = counts.maxOfOrNull { it.tailnet } ?: return null
        val used = minOf(counts.sumOf { it.apps + it.guests }, all)
        if (used <= 0) return null
        val share = when {
            used < all -> "$used of the $all tagged devices"
            used == 1L -> "the only tagged device"
            else -> "all $all tagged devices"
        }
        return "Your shareable apps and your guests' devices use $share in your Tailscale network. Tailscale's plans include a set number and charge for more."
    }
}
