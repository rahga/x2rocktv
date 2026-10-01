package com.rahga.x2rock.smapi

/**
 * A service's rating rules, fetching only on a cache miss. The Kotlin analogue of `x2rock`'s
 * `catalogue.rs` ratings half — not its full search/category cache, which x2rocktv has no
 * use for and doesn't otherwise have.
 */
class RatingsCatalogue(
    private val smapi: SmapiClient,
    private val store: RatingsStore = RatingsStore.None,
) {
    suspend fun ratingsFor(service: Service): List<RatingsMatch> {
        store.loadRatings(service.id)?.let { return it }
        val fetched = smapi.ratings(service)
        store.saveRatings(service.id, fetched)
        return fetched
    }
}
