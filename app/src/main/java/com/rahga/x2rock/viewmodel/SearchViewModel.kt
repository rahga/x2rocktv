package com.rahga.x2rock.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rahga.x2rock.apple.AppleMusicItem
import com.rahga.x2rock.apple.ITunesSearch
import com.rahga.x2rock.lan.SonosHousehold
import com.rahga.x2rock.lan.StreamStart
import com.rahga.x2rock.store.PrimaryAccounts
import com.rahga.x2rock.store.PreferredServices
import com.rahga.x2rock.store.RecentSearches
import com.rahga.x2rock.store.preferredRank
import com.rahga.x2rock.store.searchedAccounts
import com.rahga.x2rock.smapi.Category
import com.rahga.x2rock.smapi.Item
import com.rahga.x2rock.smapi.LinkedService
import com.rahga.x2rock.smapi.ServiceContent
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.Locale
import javax.inject.Inject

/**
 * One search across every music service the household has — what Sonos's own app does, and what
 * typing on a television keyboard once per service made unbearable.
 *
 * Each service that publishes search categories is asked for its tracks and its albums (or the
 * nearest two categories it has), and Apple Music through Apple's public search; answers fill in
 * section by section as they come, so a slow service holds up only its own section. Services the
 * household signed in to come first, then the anonymous ones; a service that fails or stays silent
 * is left out and counted rather than holding the list.
 *
 * A press plays a track or a station, resumes an audiobook, and opens anything else — an album, an
 * artist — in that service's own browser, where its tracks can be played. A hold or Menu opens the
 * item's menu ([ServiceItemMenu]), and each service's section ends in "More from" it, which opens
 * that service's own search for the same term with every category and every page.
 * Searched on submit, never per keystroke: that would be thirty requests a letter.
 */
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val household: SonosHousehold,
    private val itunes: ITunesSearch,
    savedStateHandle: SavedStateHandle,
    private val primaryAccounts: PrimaryAccounts,
    private val preferredServices: PreferredServices,
    private val recentSearches: RecentSearches,
    private val appleMusic: com.rahga.x2rock.store.AppleMusicSetting,
) : ViewModel() {

    private val room = RoomTarget(household, checkNotNull(savedStateHandle["groupId"]))

    /** One result, whichever search it came from. */
    sealed interface Hit {
        val key: String
        val title: String
        val subtitle: String?
        val artUrl: String?

        // Computed once: each is read by the list's keys and every recomposition as sections arrive.
        data class Service(val linked: LinkedService, val item: Item) : Hit {
            override val key = "${linked.key()}:${item.id}"
            override val title = item.title
            override val subtitle = listOfNotNull(kindLabel(item.itemType), item.summary).joinToString(" · ").ifEmpty { null }
            override val artUrl = item.artUrl
        }

        data class Apple(val item: AppleMusicItem) : Hit {
            override val key = "apple:${item.objectId}"
            override val title = item.title
            override val subtitle = listOfNotNull(kindLabel(item.kind.loadType), item.artist).joinToString(" · ")
            override val artUrl = item.artworkUrl
        }
    }

    /**
     * One service's answers, under its name. [key] tells two accounts of one service apart: both
     * are named, say, "Spotify", and a list keyed on the name alone threw on the second section.
     */
    data class Section(val name: String, val signedIn: Boolean, val hits: List<Hit>, val key: String) {
        /** The service, whichever account answered: what [PreferredServices] ranks. */
        val serviceId: String get() = key.substringBefore(':')
    }

    data class Results(
        val sections: List<Section> = emptyList(),
        /** Searches still out. */
        val pending: Int = 0,
        /** Services that failed or did not answer in time. */
        val silent: Int = 0,
        val searched: Boolean = false,
        /**
         * Whether the first section is where the list should start: no service still out would rank
         * above it — preferred, or signed in, or merely earlier by name. Until then focus waits, so it
         * lands on the top section rather than on whichever answered first. Only preferred services
         * were waited for until 2026-10-14; with none set, focus went to the first to answer (Deezer
         * at the office) and the services sorting in above it pushed it out of sight (P-34).
         */
        val leadSettled: Boolean = true,
        /** The services still out, each as the section it would be: what [sectionOrder] ranks. */
        val waiting: List<Section> = emptyList(),
    )

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _results = MutableStateFlow(Results())
    val results: StateFlow<Results> = _results.asStateFlow()

    private val _notice = TransientNotice(viewModelScope)
    private val guard = StartGuard(viewModelScope, _notice)
    /** The hit being started, so its row can say so. */
    val starting: StateFlow<String?> = guard.starting
    val notice: StateFlow<String?> = _notice.text

    /** The last read of the household's stored service sign-ins; see [SignInsNote]. */
    val signIns: StateFlow<com.rahga.x2rock.lan.StoredAccountsRead?> = household.accountsRead

    private val country = Locale.getDefault().country.ifEmpty { "US" }
    private var job: Job? = null

    /** This device's recent terms, newest first, offered under the field before a search. */
    val recent: StateFlow<List<String>> = recentSearches.terms

    fun setQuery(text: String) { _query.value = text }

    /** A recent [term], searched again: one press for what once took a keyboard. */
    fun searchAgain(term: String) {
        _query.value = term
        search()
    }

    fun forget(term: String) = recentSearches.remove(term)

    fun search() {
        val term = _query.value.trim().ifEmpty { return }
        recentSearches.add(term)
        job?.cancel()
        job = viewModelScope.launch {
            // Side by side: neither needs the other, and the Apple answer is a speaker round trip.
            val appleKnown = async { attempt { appleMusic.shown() }.getOrDefault(false) }
            // A service's primary account alone, where this device has one: see searchedAccounts.
            val services = attempt { searchedAccounts(household.searchableServices(), primaryAccounts.primaries.value) }.getOrElse {
                _results.value = Results(searched = true)
                return@launch _notice.failure("read this system's services", it)
            }
            val apple = appleKnown.await()
            val order = preferredServices.order.value
            // Each service as the section it will be, and the search that fills it in.
            val asks: List<Pair<Section, suspend () -> List<Hit>>> =
                services.map { linked -> Section(linked.service.name, linked.added, emptyList(), linked.key()) to suspend { searchOne(linked, term) } } +
                    if (apple) listOf(Section("Apple Music", signedIn = true, hits = emptyList(), key = "apple") to suspend { searchApple(term) }) else emptyList()
            _results.value = Results(pending = asks.size, searched = true, waiting = asks.map { it.first }, leadSettled = false)
            // At most a few services at once, per search: a household carries a hundred anonymous
            // radio services, and each search is up to two requests to every one. Its own permits,
            // so a search never waits on the one it replaced.
            val requests = Semaphore(MAX_CONCURRENT_SERVICES)
            // Asked in the order they will be shown, so the services that decide where focus starts
            // hold the first permits: preferred, then signed in, then the rest, each by name.
            coroutineScope {
                asks.sortedWith(compareBy(sectionOrder(order)) { it.first })
                    .forEach { (section, search) -> launch { arrive(requests, order, section, search) } }
            }
        }
    }

    /**
     * Fold one service's answer in: its section in place if it found anything, a count if it did
     * not answer. A service with no search at all answers an empty list and simply has no section.
     */
    private suspend fun arrive(requests: Semaphore, order: List<String>, pending: Section, search: suspend () -> List<Hit>) {
        val hits = requests.withPermit {
            withTimeoutOrNull(SERVICE_TIMEOUT_MILLIS) { attempt { search() }.getOrNull() }
        }
        // Cancellation while waiting for a permit or timing out must not publish into a newer search.
        currentCoroutineContext().ensureActive()
        _results.update { r ->
            val sections = if (hits.isNullOrEmpty()) r.sections
            else (r.sections + pending.copy(hits = hits)).sortedWith(sectionOrder(order))
            val waiting = r.waiting - pending
            r.copy(
                sections = sections, pending = r.pending - 1, silent = r.silent + if (hits == null) 1 else 0,
                waiting = waiting, leadSettled = leadSettled(sections, waiting, order),
            )
        }
    }

    private suspend fun searchOne(linked: LinkedService, term: String): List<Hit> {
        val available = household.serviceCategories(linked)
        return coroutineScope {
            pickCategories(available).map { category ->
                async { household.searchService(linked, category, term, count = PER_CATEGORY).items }
            }.awaitAll().flatten().distinctBy { it.id }.take(PER_SERVICE).map { Hit.Service(linked, it) }
        }
    }

    private suspend fun searchApple(term: String): List<Hit> = coroutineScope {
        listOf(AppleMusicItem.Kind.SONG, AppleMusicItem.Kind.ALBUM)
            .map { kind -> async { itunes.search(term, kind, country, limit = PER_CATEGORY) } }
            .awaitAll().flatten().distinctBy { it.objectId }.take(PER_SERVICE).map { Hit.Apple(it) }
    }

    /**
     * What a press does. Plays a track, a station or a program, resumes an audiobook; anything else
     * — an album, an artist, a show — opens through [onOpen] in that service's browser, where its
     * contents are listed rather than guessed at.
     */
    fun select(hit: Hit, onPlayed: () -> Unit, onOpen: (LinkedService, Item) -> Unit) {
        if (hit is Hit.Service && ServiceContent.opens(hit.item)) {
            return onOpen(hit.linked, hit.item)
        }
        guard.start(hit.key, "play ${hit.title}", { started -> if (started == StreamStart.SILENT) _notice.post(didNotPlay(hit.title)) else onPlayed() }) {
            when (hit) {
                is Hit.Apple -> { household.playAppleMusic(room.current(), hit.item, appleAccount()); StreamStart.PLAYING }
                is Hit.Service -> household.startServiceItem(room.current(), hit.linked, hit.item)
            }
        }
    }

    /** The menu a hold or Menu on a hit opens: play now or next, queue, and the service's own. */
    val menu = ServiceItemMenu(household, room::current, viewModelScope, _notice, guard) { target ->
        when (target) {
            is ServiceItemMenu.Target.Service -> Hit.Service(target.linked, target.item).key
            is ServiceItemMenu.Target.Apple -> Hit.Apple(target.item).key
        }
    }

    fun openMenu(hit: Hit) = menu.show(
        when (hit) {
            is Hit.Service -> ServiceItemMenu.Target.Service(hit.linked, hit.item)
            is Hit.Apple -> ServiceItemMenu.Target.Apple(hit.item)
        }
    )

    private suspend fun appleAccount(): String = household.appleMusicAccount() ?: error(NO_ACCOUNT)

    companion object {
        private const val SERVICE_TIMEOUT_MILLIS = 10_000L
        private const val PER_CATEGORY = 6
        private const val PER_SERVICE = 8
        private const val MAX_CONCURRENT_SERVICES = 8

        /** The order categories are worth asking for, most wanted first. */
        private val PREFERRED = listOf(
            "tracks", "albums", "artists", "playlists", "stations", ServiceContent.LIVE_STATIONS, "podcasts", "audiobooks",
        )

        /**
         * Two categories per service: its two most wanted, or whatever it has first. Two, because
         * every one more is another request to every service on every search.
         */
        internal fun pickCategories(available: List<Category>): List<Category> {
            val preferred = PREFERRED.mapNotNull { id -> available.firstOrNull { it.id == id } }
            return (preferred.ifEmpty { available }).take(2)
        }

        /**
         * Whether the top of [sections] is where focus should start: there is one, and none of the
         * services still [waiting] would sort above it.
         */
        internal fun leadSettled(sections: List<Section>, waiting: List<Section>, preferred: List<String>): Boolean {
            val first = sections.firstOrNull() ?: return false
            return waiting.none { sectionOrder(preferred).compare(it, first) < 0 }
        }

        /**
         * This device's preferred services first, in its order; then signed-in services — the
         * household chose those — then the rest, each by name.
         */
        internal fun sectionOrder(preferred: List<String>) =
            compareBy<Section>({ preferredRank(it.serviceId, preferred) }, { !it.signedIn }, { it.name.lowercase() })
    }
}
