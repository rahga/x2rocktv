package com.rahga.x2rock.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rahga.x2rock.apple.AppleMusicItem
import com.rahga.x2rock.apple.ITunesSearch
import com.rahga.x2rock.lan.SonosHousehold
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
) : ViewModel() {

    private val groupId: String = checkNotNull(savedStateHandle["groupId"])

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
    data class Section(val name: String, val signedIn: Boolean, val hits: List<Hit>, val key: String)

    data class Results(
        val sections: List<Section> = emptyList(),
        /** Searches still out. */
        val pending: Int = 0,
        /** Services that failed or did not answer in time. */
        val silent: Int = 0,
        val searched: Boolean = false,
    )

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _results = MutableStateFlow(Results())
    val results: StateFlow<Results> = _results.asStateFlow()

    private val _starting = MutableStateFlow<String?>(null)
    val starting: StateFlow<String?> = _starting.asStateFlow()

    private val _notice = TransientNotice(viewModelScope)
    val notice: StateFlow<String?> = _notice.text

    private val country = Locale.getDefault().country.ifEmpty { "US" }
    private var job: Job? = null

    fun setQuery(text: String) { _query.value = text }

    fun search() {
        val term = _query.value.trim().ifEmpty { return }
        job?.cancel()
        job = viewModelScope.launch {
            // Side by side: neither needs the other, and the Apple answer is a speaker round trip.
            val appleKnown = async { runCatching { household.appleMusicAccount() }.getOrNull() != null }
            val services = runCatching { household.searchableServices() }.getOrElse {
                _results.value = Results(searched = true)
                return@launch _notice.failure("read this system's services", it)
            }
            val apple = appleKnown.await()
            _results.value = Results(pending = services.size + if (apple) 1 else 0, searched = true)
            // At most a few services at once, per search: a household carries a hundred anonymous
            // radio services, and each search is up to two requests to every one. Its own permits,
            // so a search never waits on the one it replaced.
            val requests = Semaphore(MAX_CONCURRENT_SERVICES)
            coroutineScope {
                services.forEach { linked ->
                    launch { arrive(requests, linked.service.name, linked.key(), linked.added) { searchOne(linked, term) } }
                }
                if (apple) launch { arrive(requests, "Apple Music", "apple", signedIn = true) { searchApple(term) } }
            }
        }
    }

    /**
     * Fold one service's answer in: its section in place if it found anything, a count if it did
     * not answer. A service with no search at all answers an empty list and simply has no section.
     */
    private suspend fun arrive(requests: Semaphore, name: String, key: String, signedIn: Boolean, search: suspend () -> List<Hit>) {
        val hits = requests.withPermit {
            withTimeoutOrNull(SERVICE_TIMEOUT_MILLIS) { runCatching { search() }.getOrNull() }
        }
        // `runCatching` also swallows the cancellation of a search replaced by a newer one; without
        // this its late answers were counted against the new search's pending and silent totals.
        currentCoroutineContext().ensureActive()
        _results.update { r ->
            val sections = if (hits.isNullOrEmpty()) r.sections
            else (r.sections + Section(name, signedIn, hits, key)).sortedWith(sectionOrder)
            r.copy(sections = sections, pending = r.pending - 1, silent = r.silent + if (hits == null) 1 else 0)
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
        if (_starting.value != null) return
        viewModelScope.launch {
            _starting.value = hit.key
            val started = runCatching {
                when (hit) {
                    is Hit.Apple -> household.playAppleMusic(groupId, hit.item, appleAccount())
                    is Hit.Service -> household.startServiceItem(groupId, hit.linked, hit.item)
                }
            }
            _starting.value = null
            started.onSuccess { onPlayed() }.onFailure { _notice.failure("play ${hit.title}", it) }
        }
    }

    /** The menu a hold or Menu on a hit opens: play now or next, queue, and the service's own. */
    val menu = ServiceItemMenu(household, groupId, viewModelScope, _notice, _starting) { target ->
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
        private val PREFERRED = listOf("tracks", "albums", "artists", "playlists", "stations", "podcasts", "audiobooks")

        /**
         * Two categories per service: its two most wanted, or whatever it has first. Two, because
         * every one more is another request to every service on every search.
         */
        internal fun pickCategories(available: List<Category>): List<Category> {
            val preferred = PREFERRED.mapNotNull { id -> available.firstOrNull { it.id == id } }
            return (preferred.ifEmpty { available }).take(2)
        }

        /** Signed-in services first — the household chose those — then the rest, each by name. */
        internal val sectionOrder = compareBy<Section>({ !it.signedIn }, { it.name.lowercase() })
    }
}
