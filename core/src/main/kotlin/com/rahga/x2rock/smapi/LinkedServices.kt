package com.rahga.x2rock.smapi

/**
 * A service the app can search or browse right now, paired with the credential it does so
 * with. Either the service is [Auth.ANONYMOUS] (no credential needed) or the household stores
 * a token for it, read out of [StoredAccounts]. Services whose policy needs a login the app
 * cannot drive and for which the household holds no token are not here — they would only ever
 * answer "needs an account linked", which is not a thing a TV remote can fix.
 */
data class LinkedService(
    val service: Service,
    /** `null` for an anonymous service; otherwise the household's own stored token. */
    val token: Token?,
    /** `sn_<serial>` for an enqueue/loadContent against the household's account; `null` if anonymous. */
    val accountId: String?,
    /**
     * The account's selector out of `Username<i>` ([StoredAccount.accountKey]) — what a queued
     * item's cdudn names to pick this account among a service's several. `""` for an anonymous
     * service or one whose selector is the `0` that means "the service, no account".
     */
    val selector: String,
    /** The account nickname, to tell two accounts of one service apart in a picker; `""` if none. */
    val nickname: String,
)

/**
 * Which of [services] the app can actually use, each carrying its credential.
 *
 * Match is by numeric service id: a [StoredAccount.serviceId] is the same number a
 * [Service.id] carries. A service with two stored accounts yields two entries (the household
 * had two Amazon Music accounts), both kept so a picker can choose. [householdLong] is the
 * long household id the SMAPI `loginToken` header wants — not the short form the decrypt key
 * uses; it may be `null` when searching a cached catalogue with no player reachable.
 *
 * Ordered alphabetically by service name, which is Sonos's own order and the only one there
 * is — nothing here hoists a favourite or a "primary".
 */
fun linkedServices(
    services: List<Service>,
    accounts: List<StoredAccount>,
    householdLong: String?,
): List<LinkedService> {
    val byService = accounts.filter { it.hasToken }.groupBy { it.serviceId }
    val out = mutableListOf<LinkedService>()
    for (service in services) {
        val serviceId = service.id.toLongOrNull()
        val stored = serviceId?.let { byService[it] }.orEmpty()
        when {
            stored.isNotEmpty() -> stored.forEach { account ->
                out += LinkedService(
                    service = service,
                    token = Token(account.token, account.key, householdLong),
                    accountId = "sn_${account.serial}",
                    selector = account.accountKey,
                    nickname = account.nickname,
                )
            }
            // An anonymous service needs no credential and is always usable.
            service.auth == Auth.ANONYMOUS ->
                out += LinkedService(service = service, token = null, accountId = null, selector = "", nickname = "")
            // Everything else needs a login the app cannot drive and has no stored token for.
        }
    }
    return out.sortedBy { it.service.name.lowercase() }
}
