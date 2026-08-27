package com.rahga.x2rock.cli

import com.rahga.x2rock.auth.SonosClientConfig
import com.rahga.x2rock.model.Group
import com.rahga.x2rock.network.AuthInterceptor
import com.rahga.x2rock.network.SonosApiService
import com.rahga.x2rock.repository.SonosAuthRepository
import com.rahga.x2rock.repository.SonosRepository
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

/**
 * The object graph, built by hand — the same shape Hilt assembles in the TV app.
 *
 * [householdOverride], when set (from `--household`/`-H`), names a room in the household to use
 * for this invocation only — it is resolved to an id on first use and never written to disk,
 * unlike `x2rock config --household`.
 */
class Sonos(val config: CliConfig, private val householdOverride: String? = null) {
    val tokens = FileTokenStore()

    private val baseClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .dispatcher(Dispatcher().apply { maxRequestsPerHost = 10 })
        .build()

    val auth = SonosAuthRepository(
        tokenStore = tokens,
        config = SonosClientConfig(
            clientId = config.clientId ?: "",
            clientSecret = config.clientSecret ?: ""
        ),
        okHttpClient = baseClient
    )

    val repo: SonosRepository by lazy {
        val client = baseClient.newBuilder().addInterceptor(AuthInterceptor(tokens)).build()
        val api = Retrofit.Builder()
            .baseUrl("https://api.ws.sonos.com/control/api/v1/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(SonosApiService::class.java)
        SonosRepository(api, auth).apply { preferredHouseholdId = config.householdId }
    }

    val hasClientCredentials: Boolean
        get() = !config.clientId.isNullOrBlank() && !config.clientSecret.isNullOrBlank()

    /** Fetches the groups and returns them with each group's member names for matching. */
    suspend fun groups(): List<Group> {
        householdOverride?.let { repo.preferredHouseholdId = resolveHouseholdId(repo, it) }
        return repo.getGroups().getOrThrow()
    }

    /** Lets an OkHttp-backed process exit promptly instead of waiting on idle pool threads. */
    fun shutdown() {
        baseClient.dispatcher.executorService.shutdown()
        baseClient.connectionPool.evictAll()
    }
}

/**
 * Resolves a room name the way a person types it: exact group name, then a group whose name
 * starts with it, then a group containing a player of that name ("Kitchen" finds "Kitchen + 1").
 * Case-insensitive throughout. Returns null when nothing matches; throws when it is ambiguous.
 */
fun matchRoom(groups: List<Group>, query: String, playerName: (String) -> String): Group? {
    val q = query.trim().lowercase()
    groups.firstOrNull { it.name.lowercase() == q }?.let { return it }

    val byPrefix = groups.filter { it.name.lowercase().startsWith(q) }
    if (byPrefix.size == 1) return byPrefix.single()

    val byPlayer = groups.filter { g -> g.playerIds.any { playerName(it).lowercase() == q } }
    if (byPlayer.size == 1) return byPlayer.single()

    val candidates = (byPrefix + byPlayer).distinctBy { it.id }
    if (candidates.size > 1) {
        throw AmbiguousRoomException(query, candidates.map { it.name })
    }
    return null
}

class AmbiguousRoomException(val query: String, val candidates: List<String>) :
    RuntimeException("\"$query\" matches ${candidates.size} rooms: ${candidates.joinToString()}")

class NoSuchHouseholdException(val query: String) :
    RuntimeException("No household has a room named \"$query\". Run `x2rock households` to see what's available.")

class AmbiguousHouseholdException(val query: String, val candidates: List<String>) :
    RuntimeException("More than one household has a room named \"$query\": ${candidates.joinToString()}")

/** Finds the household containing a room named [query] (case-insensitive), by any player in it. */
suspend fun resolveHouseholdId(repo: SonosRepository, query: String): String =
    matchHousehold(repo.listHouseholds().getOrThrow(), query)

/** Pure matching logic behind [resolveHouseholdId], split out so it's testable without the network. */
fun matchHousehold(households: Map<String, List<String>>, query: String): String {
    val match = households.entries.filter { (_, players) -> players.any { it.equals(query, ignoreCase = true) } }
    return when (match.size) {
        1 -> match.single().key
        0 -> throw NoSuchHouseholdException(query)
        else -> throw AmbiguousHouseholdException(query, match.flatMap { it.value })
    }
}
