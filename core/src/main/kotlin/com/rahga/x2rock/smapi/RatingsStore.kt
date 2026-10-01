package com.rahga.x2rock.smapi

/**
 * Where a service's rating rules are remembered, so a rate press does not cost a manifest and
 * presentation-map fetch every time. The same seam `SeedStore` and `Preferences` are for:
 * `:core` stays framework-free, and `:app` supplies the real, Android-backed implementation.
 *
 * Only the ratings half is cached — not the service list `ListAvailableServices` itself
 * answers with, which is a single cheap LAN call and cheap enough to just ask fresh each time,
 * the same way `x2rock`'s own CLI effectively does (a fresh process every invocation).
 */
interface RatingsStore {

    /**
     * `null` means never asked; an empty list means asked and the service published none —
     * both are worth telling apart, the same distinction `x2rock`'s `ratings_cached` makes,
     * because most services publish no ratings at all and that is knowledge worth keeping
     * rather than a cache miss to retry on every press.
     */
    fun loadRatings(serviceId: String): List<RatingsMatch>?

    fun saveRatings(serviceId: String, ratings: List<RatingsMatch>)

    /** Remembers nothing; every rate press re-fetches. */
    object None : RatingsStore {
        override fun loadRatings(serviceId: String): List<RatingsMatch>? = null
        override fun saveRatings(serviceId: String, ratings: List<RatingsMatch>) = Unit
    }
}
