package com.rahga.x2rock.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rahga.x2rock.lan.SonosHousehold
import com.rahga.x2rock.store.PrimaryAccounts
import com.rahga.x2rock.smapi.Category
import com.rahga.x2rock.smapi.Item
import com.rahga.x2rock.smapi.ItemPage
import com.rahga.x2rock.smapi.LinkedService
import com.rahga.x2rock.smapi.ServiceContent
import com.rahga.x2rock.smapi.Shelf
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The household's own music services — Qobuz, TIDAL, Deezer, Amazon, Saavn and the rest —
 * browsed and searched over SMAPI with the household's stored token, and played in the room
 * through its own account. Apple Music is not here: it has no usable SMAPI credential and is
 * searched through Apple's public catalogue on its own screen instead.
 *
 * A service opens on **its own library** — Deezer's Flow and Favourite Tracks, Qobuz's playlists,
 * TIDAL's collection — because on a remote browsing is the cheap path and typing the expensive
 * one. It used to open on a search field whenever the service published search categories, which
 * left every one of those libraries unreachable (compared against the Sonos app, 2026-10-08).
 * Search is a level of its own, entered from a button above the library; a service whose root is
 * empty (Sonos Radio's is, over SMAPI) opens straight onto it instead.
 *
 * Each level keeps what it showed, so Back is instant and returns to the same rows. A press on a
 * track plays it in place of the queue, a press on a container opens it, and a hold adds it to
 * the queue.
 */
@HiltViewModel
class ServiceBrowseViewModel @Inject constructor(
    private val household: SonosHousehold,
    savedStateHandle: SavedStateHandle,
    private val primaryAccounts: PrimaryAccounts,
) : ViewModel() {

    private val room = RoomTarget(household, checkNotNull(savedStateHandle["groupId"]))

    /**
     * Opened from search on one container — an album, an artist — rather than on the service list:
     * the service's [LinkedService.key] and the container's id and title. Back then climbs out of
     * that container and closes, returning to the search, rather than wandering the service.
     */
    private val entry: Triple<String, String, String>? = run {
        val service = savedStateHandle.get<String>("service")?.takeIf { it.isNotEmpty() } ?: return@run null
        val container = savedStateHandle.get<String>("container")?.takeIf { it.isNotEmpty() } ?: return@run null
        Triple(service, container, savedStateHandle.get<String>("title").orEmpty())
    }

    /**
     * The Sonos favourite the entry container is, when it was opened from Browse's favourites: its
     * Play and Shuffle load the favourite itself, exactly as pressing it there always has, so
     * playing never depends on the service's browse having worked.
     */
    private val entryFavorite: String? = savedStateHandle.get<String>("favorite")?.takeIf { it.isNotEmpty() }

    /**
     * Opened from search's "More from" on one service: its [LinkedService.key] and the term, so the
     * service's own search opens with every category and every page of it. Back from there returns
     * to the search it came from.
     */
    private val entrySearch: Pair<String, String>? = run {
        val service = savedStateHandle.get<String>("service")?.takeIf { it.isNotEmpty() } ?: return@run null
        val query = savedStateHandle.get<String>("query")?.takeIf { it.isNotBlank() } ?: return@run null
        service to query
    }

    sealed interface Services {
        data object Loading : Services
        data class Ready(
            val services: List<LinkedService>,
            /** Whether Apple Music is offered too: it is searched through Apple, not SMAPI, on its own screen. */
            val appleMusic: Boolean = false,
        ) : Services
        data class Failed(val message: String) : Services
    }

    sealed interface Results {
        data object Idle : Results
        data object Loading : Results
        /**
         * [total] is what the service says the whole list holds; [items] may be only its first
         * pages. [nextIndex] is how many the service has sent, repeats included — where its next
         * page starts, which the distinct [items] fall short of when it named one twice.
         */
        data class Found(val items: List<Item>, val total: Int = items.size, val nextIndex: Int = items.size) : Results {
            val hasMore: Boolean get() = items.size < total
        }
        data class Failed(val message: String) : Results
    }

    /** What a container's Play and Shuffle start: a Sonos favourite, or the container itself. */
    private sealed interface Whole {
        data class Favorite(val id: String) : Whole
        data class Container(val item: Item) : Whole
    }

    /**
     * One level of the trail Back climbs: a container ([id] and its [title]), or the search. Each
     * keeps its own [results], so returning to it shows the same rows without asking again.
     */
    private class Level(val id: String?, val title: String, val whole: Whole? = null) {
        val isSearch get() = id == null
        var results: Results = Results.Idle
        /** The row last opened from this level, for Back to land on rather than the first. */
        var opened: String? = null
    }

    private val _services = MutableStateFlow<Services>(Services.Loading)
    val services: StateFlow<Services> = _services.asStateFlow()

    /** The service being used, or `null` while its list is showing. */
    private val _active = MutableStateFlow<LinkedService?>(null)
    val active: StateFlow<LinkedService?> = _active.asStateFlow()

    private val _categories = MutableStateFlow<List<Category>>(emptyList())
    val categories: StateFlow<List<Category>> = _categories.asStateFlow()

    private val _category = MutableStateFlow<Category?>(null)
    val category: StateFlow<Category?> = _category.asStateFlow()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _results = MutableStateFlow<Results>(Results.Idle)
    val results: StateFlow<Results> = _results.asStateFlow()

    /** Whether the search level is the one showing — the field and categories, rather than a library. */
    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching.asStateFlow()

    /** The container showing, under the service's own name: what the screen says it is in. */
    private val _place = MutableStateFlow<String?>(null)
    val place: StateFlow<String?> = _place.asStateFlow()

    /** The id of the container showing, so a menu does not offer to open the place it is in. */
    private val _here = MutableStateFlow<String?>(null)
    val here: StateFlow<String?> = _here.asStateFlow()

    /** The row focus belongs on when a level is shown again: the one that was opened from it. */
    private val _returnTo = MutableStateFlow<String?>(null)
    val returnTo: StateFlow<String?> = _returnTo.asStateFlow()

    /** Whether the container showing can be played whole — an album, a playlist, a favourite. */
    private val _playsWhole = MutableStateFlow(false)
    val playsWhole: StateFlow<Boolean> = _playsWhole.asStateFlow()

    private val _starting = MutableStateFlow<String?>(null)
    val starting: StateFlow<String?> = _starting.asStateFlow()

    private val _notice = TransientNotice(viewModelScope)
    val notice: StateFlow<String?> = _notice.text

    private val trail = ArrayDeque<Level>()

    /** The service's own page, where its SMAPI root is empty — see [SonosHousehold.serviceShelves]. */
    private var shelves: List<Shelf> = emptyList()
    private var job: Job? = null
    private var moreJob: Job? = null

    init {
        viewModelScope.launch {
            // Side by side: the Apple answer is a speaker round trip neither the list nor an entry
            // container needs to wait for.
            val apple = async { runCatching { household.appleMusicAccount() != null }.getOrDefault(false) }
            val services = runCatching { household.searchableServices() }
            entry?.let { (key, container, title) ->
                services.getOrNull()?.firstOrNull { it.key() == key }?.let { service ->
                    _active.value = service
                    browse(container, title, entryFavorite?.let { Whole.Favorite(it) })
                }
            }
            if (entry == null) entrySearch?.let { (key, query) ->
                services.getOrNull()?.firstOrNull { it.key() == key }?.let { service ->
                    _active.value = service
                    _categories.value = runCatching { household.serviceCategories(service) }.getOrDefault(emptyList())
                    _category.value = _categories.value.firstOrNull()
                    _query.value = query
                    search()
                }
            }
            _services.value = services.fold(
                onSuccess = { Services.Ready(it, apple.await()) },
                onFailure = { Services.Failed("Couldn't read this system's services: ${it.message ?: it}") },
            )
        }
    }

    /**
     * Open a service on its library, learning its search categories alongside. A root that comes
     * back empty or refused, from a service that can be searched, gives way to the search: Sonos
     * Radio answers its SMAPI root with nothing at all.
     */
    fun open(service: LinkedService) {
        _active.value = service
        _query.value = ""
        _categories.value = emptyList()
        _category.value = null
        trail.clear()
        shelves = emptyList()
        job?.cancel()
        moreJob?.cancel()
        val root = Level("root", service.service.name).also { trail.addLast(it) }
        show(root)
        publish(root, Results.Loading)
        job = viewModelScope.launch {
            val cats = async { runCatching { household.serviceCategories(service) }.getOrDefault(emptyList()) }
            val page = runCatching { household.browseService(service, "root") }
            _categories.value = cats.await()
            _category.value = _categories.value.firstOrNull()
            val empty = page.getOrNull()?.items.isNullOrEmpty()
            // An empty root may still have a page of its own, from the service's browse endpoint:
            // Sonos Radio's shelves, which the Sonos app opens it on.
            if (empty) {
                shelves = runCatching { household.serviceShelves(service) }.getOrDefault(emptyList())
                if (shelves.isNotEmpty()) {
                    publish(root, Results.Found(shelves.mapIndexed { i, shelf ->
                        Item("$SHELF$i", shelf.title, "container", stationCount(shelf.items.size), shelf.items.first().artUrl, container = true)
                    }))
                    return@launch
                }
            }
            if (empty && _categories.value.isNotEmpty()) {
                trail.clear()
                show(Level(null, service.service.name).also { trail.addLast(it) })
                return@launch
            }
            publish(root, page.fold(
                onSuccess = { found(it) },
                onFailure = { Results.Failed("${service.service.name} wouldn't open: ${it.message ?: it}") },
            ))
        }
    }

    /** Step into the search, above whatever library level is showing. */
    fun openSearch() {
        if (_active.value == null || _categories.value.isEmpty() || trail.lastOrNull()?.isSearch == true) return
        job?.cancel()
        moreJob?.cancel()
        show(Level(null, "Search").also { trail.addLast(it) })
    }

    fun setCategory(category: Category) {
        if (_category.value == category) return
        _category.value = category
        if (_query.value.isNotBlank()) search()
    }

    fun setQuery(text: String) { _query.value = text }

    fun search() {
        val service = _active.value ?: return
        val category = _category.value ?: return
        val term = _query.value.trim().ifEmpty { return }
        // Whatever was opened from an earlier search is behind this one now.
        while (trail.isNotEmpty() && trail.last().isSearch.not() && trail.any { it.isSearch }) trail.removeLast()
        if (trail.lastOrNull()?.isSearch != true) trail.addLast(Level(null, "Search"))
        val level = trail.last()
        show(level)
        job?.cancel()
        moreJob?.cancel()
        job = viewModelScope.launch {
            publish(level, Results.Loading)
            publish(level, runCatching { household.searchService(service, category, term) }.fold(
                onSuccess = { found(it) },
                onFailure = { Results.Failed("${service.service.name} wouldn't answer: ${it.message ?: it}") },
            ))
        }
    }

    /** Open a container. [title] is what names it in the trail Back follows back out. */
    fun browse(id: String, title: String) = browse(id, title, null)

    private fun browse(id: String, title: String, whole: Whole?) {
        val service = _active.value ?: return
        val level = Level(id, title, whole).also { trail.addLast(it) }
        // A shelf's stations came with the page: nothing to ask for.
        shelves.getOrNull(id.removePrefix(SHELF).toIntOrNull()?.takeIf { id.startsWith(SHELF) } ?: -1)?.let { shelf ->
            show(level)
            publish(level, Results.Found(shelf.items))
            return
        }
        show(level)
        job?.cancel()
        moreJob?.cancel()
        job = viewModelScope.launch {
            publish(level, Results.Loading)
            publish(level, runCatching { household.browseService(service, id) }.fold(
                onSuccess = { found(it) },
                onFailure = { Results.Failed("${service.service.name} wouldn't open ${title}: ${it.message ?: it}") },
            ))
        }
    }

    /**
     * Fetch the next page of what is showing, once the list nears its end. A service answers 100
     * at a time; a playlist of 300 used to stop at the hundredth row with nothing to say so.
     */
    fun loadMore() {
        val service = _active.value ?: return
        val level = trail.lastOrNull() ?: return
        val shown = level.results as? Results.Found ?: return
        if (!shown.hasMore || moreJob?.isActive == true) return
        moreJob = viewModelScope.launch {
            val next = runCatching {
                if (level.isSearch) {
                    val category = _category.value ?: return@launch
                    household.searchService(service, category, _query.value.trim(), index = shown.nextIndex)
                } else {
                    household.browseService(service, level.id!!, index = shown.nextIndex)
                }
            }.getOrNull() ?: return@launch
            // A service that answers an empty page past where it said the list ended has no more,
            // whatever its total claimed; believing the total would ask again on every frame.
            val total = if (next.items.isEmpty()) shown.items.size else shown.total
            publish(level, found(shown.items + next.items, total, sent = shown.nextIndex + next.items.size))
        }
    }

    /** Each service's primary account on this device; see [PrimaryAccounts]. */
    val primaries: StateFlow<Map<String, String>> = primaryAccounts.primaries

    /** The service row whose hold menu is open — "Make primary" — or `null`. */
    private val _serviceMenu = MutableStateFlow<LinkedService?>(null)
    val serviceMenu: StateFlow<LinkedService?> = _serviceMenu.asStateFlow()

    /** A hold on a service row: its menu, where it has another account to be primary over. */
    fun openServiceMenu(linked: LinkedService) {
        val all = (_services.value as? Services.Ready)?.services.orEmpty()
        if (all.count { it.service.id == linked.service.id } > 1) _serviceMenu.value = linked
    }

    fun closeServiceMenu() { _serviceMenu.value = null }

    fun makePrimary(linked: LinkedService) {
        primaryAccounts.makePrimary(linked)
        _notice.post("${linked.nickname.ifEmpty { linked.service.name }} is now ${linked.service.name}'s primary account here")
    }

    /** The menu a hold or Menu on a row opens. */
    val menu = ServiceItemMenu(household, room::current, viewModelScope, _notice, _starting) {
        (it as ServiceItemMenu.Target.Service).item.id
    }

    /** Open a hold's menu on [item], from the service showing. */
    fun openMenu(item: Item) {
        val service = _active.value ?: return
        menu.show(ServiceItemMenu.Target.Service(service, item))
    }

    /** Open [id] as a level of its own — an artist or album named by an item's menu. */
    fun openRelated(id: String, title: String, album: Boolean = false) {
        if (trail.lastOrNull()?.id == id) return
        (menu.open.value as? ServiceItemMenu.Target.Service)?.let { trail.lastOrNull()?.opened = it.item.id }
        browse(id, title, if (album) Whole.Container(Item(id, title, "album", null, null, container = true)) else null)
    }

    /**
     * Play the container showing from its start, or with [shuffle] on — the Sonos app's Play and
     * Shuffle at the head of an album. Shuffle is set first, so the load starts shuffled; Play
     * leaves the room's shuffle as it was.
     */
    fun playWhole(shuffle: Boolean, onPlayed: () -> Unit) {
        val whole = trail.lastOrNull()?.whole ?: return
        val service = _active.value ?: return
        if (_starting.value != null) return
        viewModelScope.launch {
            _starting.value = WHOLE
            val started = runCatching {
                if (shuffle) household.setShuffle(room.current(), true)
                when (whole) {
                    is Whole.Favorite -> household.loadFavorite(room.current(), whole.id)
                    is Whole.Container -> household.playServiceItem(room.current(), service, whole.item)
                }
            }
            _starting.value = null
            started.onSuccess { onPlayed() }.onFailure { _notice.failure("play ${trail.lastOrNull()?.title}", it) }
        }
    }

    /**
     * What a press does: resume an audiobook where it was left off, open any other container, or
     * play a leaf in place of the queue. An audiobook is a container of chapters, but what a
     * listener wants on pressing it is to carry on, not to pick a chapter — see
     * [ServiceContent.isResumable].
     */
    fun select(item: Item, onPlayed: () -> Unit) {
        if (ServiceContent.opens(item)) {
            trail.lastOrNull()?.opened = item.id
            browse(item.id, item.title, if (ServiceContent.canEnqueue(item)) Whole.Container(item) else null)
            return
        }
        if (_starting.value != null) return
        val service = _active.value ?: return
        viewModelScope.launch {
            _starting.value = item.id
            val started = runCatching { household.startServiceItem(room.current(), service, item) }
            _starting.value = null
            started.onSuccess { onPlayed() }.onFailure { _notice.failure("play ${item.title}", it) }
        }
    }

    /**
     * Back's effect: climb out one level at a time — a container, the search — then back to the
     * service list, and only past that does the screen close. Returns true when it handled Back.
     */
    fun back(): Boolean {
        // Entered from search, on a container or on this service's own search: its top is where
        // Back leaves, back to the search it came from.
        if ((entry != null || entrySearch != null) && trail.size <= 1) return false
        if (trail.size > 1) {
            job?.cancel()
            moreJob?.cancel()
            trail.removeLast()
            show(trail.last())
            return true
        }
        if (_active.value != null) {
            job?.cancel()
            moreJob?.cancel()
            trail.clear()
            _active.value = null
            _results.value = Results.Idle
            _searching.value = false
            _place.value = null
            _categories.value = emptyList()
            return true
        }
        return false
    }

    /** Make [level] the one showing, with whatever it last held. */
    private fun show(level: Level) {
        _searching.value = level.isSearch
        // A service's root is named by the header already; anything deeper, or a container entered
        // straight from Browse or Search, says what it is.
        _place.value = level.title.takeIf { !level.isSearch && (trail.size > 1 || entry != null) }
        _returnTo.value = level.opened
        _here.value = level.id
        _playsWhole.value = level.whole != null
        _results.value = level.results
    }

    /** Record [results] on [level], and show them if it is still the level showing. */
    private fun publish(level: Level, results: Results) {
        level.results = results
        if (trail.lastOrNull() === level) _results.value = results
    }
}

/** The id a shelf of a service's page is listed under; never a service's own id. */
private const val SHELF = "\u0000shelf:"

private fun stationCount(n: Int) = if (n == 1) "1 station" else "$n stations"

/** What [ServiceBrowseViewModel.starting] holds while a container plays whole: no row is starting. */
const val WHOLE = "\u0000whole"

/**
 * A page of a service's items for the list, each id once. The list is keyed by id, and a service
 * that names one item twice — radio directories and service browse trees both do — would otherwise
 * throw "Key was already used" and take the app down. The first occurrence is kept.
 */
private fun found(page: ItemPage) = found(page.items, page.total, sent = page.items.size)

private fun found(items: List<Item>, total: Int, sent: Int): ServiceBrowseViewModel.Results.Found {
    val unique = items.distinctBy { it.id }
    // Duplicates dropped make the list shorter than the service counted; the shortfall is not more
    // to fetch, so the total moves down with it.
    return ServiceBrowseViewModel.Results.Found(unique, (total - (items.size - unique.size)).coerceAtLeast(unique.size), nextIndex = sent)
}

/** A stable key for a linked service: the service, plus the account so two accounts of one are distinct. */
fun LinkedService.key(): String = "${service.id}:${accountId ?: "anon"}"
