package com.rahga.x2rock.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rahga.x2rock.lan.SonosHousehold
import com.rahga.x2rock.smapi.Category
import com.rahga.x2rock.smapi.Item
import com.rahga.x2rock.smapi.LinkedService
import com.rahga.x2rock.smapi.ServiceContent
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
 * searched and browsed over SMAPI with the household's stored token, and played in the room
 * through its own account. Apple Music is not here: it has no usable SMAPI credential and is
 * searched through Apple's public catalogue on its own screen instead.
 *
 * The screen is two steps: pick a service, then search it (if it publishes search categories)
 * or browse it. A press on a track plays it in place of the queue; a press on a container opens
 * it. Nothing here appends to the queue — a generic enqueue is per-service and not built yet;
 * play-now covers the case a remote reaches for.
 */
@HiltViewModel
class ServiceBrowseViewModel @Inject constructor(
    private val household: SonosHousehold,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val groupId: String = checkNotNull(savedStateHandle["groupId"])

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
        data class Found(val items: List<Item>) : Results
        data class Failed(val message: String) : Results
    }

    /** One level of a browse, so Back can climb out of a container rather than leave the service. */
    private data class Crumb(val title: String, val id: String)

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

    private val _starting = MutableStateFlow<String?>(null)
    val starting: StateFlow<String?> = _starting.asStateFlow()

    private val _notice = TransientNotice(viewModelScope)
    val notice: StateFlow<String?> = _notice.text

    private val crumbs = ArrayDeque<Crumb>()

    /**
     * The last search's results, so Back out of an album or artist opened from them returns to
     * them. Back used to set the results to Idle there, leaving the query in the field above an
     * empty list, with every hit gone.
     */
    private var searchResults: Results? = null
    private var job: Job? = null

    init {
        viewModelScope.launch {
            // Side by side: the Apple answer is a speaker round trip neither the list nor an entry
            // container needs to wait for.
            val apple = async { runCatching { household.appleMusicAccount() != null }.getOrDefault(false) }
            val services = runCatching { household.searchableServices() }
            entry?.let { (key, container, title) ->
                services.getOrNull()?.firstOrNull { it.key() == key }?.let { service ->
                    _active.value = service
                    browse(container, title)
                }
            }
            _services.value = services.fold(
                onSuccess = { Services.Ready(it, apple.await()) },
                onFailure = { Services.Failed("Couldn't read this system's services: ${it.message ?: it}") },
            )
        }
    }

    /** Open a service: learn its search categories, and browse its root when it has none. */
    fun open(service: LinkedService) {
        _active.value = service
        _query.value = ""
        _results.value = Results.Idle
        crumbs.clear()
        searchResults = null
        job?.cancel()
        job = viewModelScope.launch {
            val cats = runCatching { household.serviceCategories(service) }.getOrDefault(emptyList())
            _categories.value = cats
            _category.value = cats.firstOrNull()
            // A service with nothing to search is reached only by browsing, so start there.
            if (cats.isEmpty()) browse("root", service.service.name)
        }
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
        crumbs.clear()
        searchResults = null
        job?.cancel()
        job = viewModelScope.launch {
            _results.value = Results.Loading
            _results.value = runCatching { household.searchService(service, category.mappedId, term) }.fold(
                onSuccess = { Results.Found(it.items) },
                onFailure = { Results.Failed("${service.service.name} wouldn't answer: ${it.message ?: it}") },
            ).also { searchResults = it }
        }
    }

    /** Open a container. [title] is what names it in the trail Back follows back out. */
    fun browse(id: String, title: String) {
        val service = _active.value ?: return
        crumbs.addLast(Crumb(title, id))
        job?.cancel()
        job = viewModelScope.launch {
            _results.value = Results.Loading
            _results.value = runCatching { household.browseService(service, id) }.fold(
                onSuccess = { Results.Found(it.items) },
                onFailure = { Results.Failed("${service.service.name} wouldn't open ${title}: ${it.message ?: it}") },
            )
        }
    }

    /** Add [item] to the end of the queue, leaving what plays alone. Only what a queue can hold. */
    fun queue(item: Item) {
        val service = _active.value ?: return
        if (!ServiceContent.canEnqueue(item)) {
            _notice.post("\"${item.title}\" isn't something a queue can hold")
            return
        }
        viewModelScope.launch {
            runCatching { household.queueServiceItem(groupId, service, item) }
                .onSuccess { _notice.post("Added \"${item.title}\" to the queue") }
                .onFailure { _notice.failure("add ${item.title}", it) }
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
            browse(item.id, item.title)
            return
        }
        if (_starting.value != null) return
        val service = _active.value ?: return
        viewModelScope.launch {
            _starting.value = item.id
            val started = runCatching { household.startServiceItem(groupId, service, item) }
            _starting.value = null
            started.onSuccess { onPlayed() }.onFailure { _notice.failure("play ${item.title}", it) }
        }
    }

    /**
     * Back's effect: climb out of a browse one container at a time, then back to the service
     * list, and only past that does the screen close. Returns true when it handled Back.
     */
    fun back(): Boolean {
        // Entered on a container from search: its own top is where Back leaves, back to the search.
        if (entry != null && crumbs.size <= 1) return false
        if (crumbs.isNotEmpty()) {
            crumbs.removeLast()
            val parent = crumbs.lastOrNull()
            job?.cancel()
            val service = _active.value
            if (parent != null && service != null) {
                browseWithoutPush(service, parent)
            } else {
                // Back at the service's own top: a search service shows the results the search
                // left, a browse-only one re-reads its root.
                if (_categories.value.isEmpty()) browse("root", _active.value?.service?.name.orEmpty())
                else _results.value = searchResults ?: Results.Idle
            }
            return true
        }
        if (_active.value != null) {
            _active.value = null
            _results.value = Results.Idle
            _categories.value = emptyList()
            return true
        }
        return false
    }

    /** Re-open [parent] without pushing it onto the trail again (Back already popped it). */
    private fun browseWithoutPush(service: LinkedService, parent: Crumb) {
        job = viewModelScope.launch {
            _results.value = Results.Loading
            _results.value = runCatching { household.browseService(service, parent.id) }.fold(
                onSuccess = { Results.Found(it.items) },
                onFailure = { Results.Failed("${service.service.name} wouldn't reopen ${parent.title}") },
            )
        }
    }
}

/** A stable key for a linked service: the service, plus the account so two accounts of one are distinct. */
fun LinkedService.key(): String = "${service.id}:${accountId ?: "anon"}"
