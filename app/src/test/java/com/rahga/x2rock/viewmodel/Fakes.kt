package com.rahga.x2rock.viewmodel

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import com.rahga.x2rock.channel.ChannelSync
import com.rahga.x2rock.channel.RoomTile
import com.rahga.x2rock.media.NowPlayingPublisher
import com.rahga.x2rock.model.PlaybackActions
import com.rahga.x2rock.model.Group
import com.rahga.x2rock.store.Preferences

/**
 * The main dispatcher every view model launches on: set once for the whole test run, and never
 * reset.
 *
 * It used to be reset after each test, and that raced. A view model's scope is never cancelled
 * here, so its collectors go on resuming onto Main from the household's threads after a test
 * ends, and `resetMain` threw "Dispatchers.Main is used concurrently with setting it" — seen in
 * `HomeViewModelTest` and `PlayerViewModelTest`, about one full run in four, failing tests whose
 * assertions had all passed. Every class wants the same Unconfined Main, so there is nothing to
 * reset to between them.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule : TestWatcher() {
    override fun starting(description: Description) = setOnce()

    private companion object {
        @Volatile private var set = false

        @Synchronized fun setOnce() {
            if (set) return
            Dispatchers.setMain(Dispatchers.Unconfined)
            set = true
        }
    }
}

/** A monotonic clock the JVM has; Android's SystemClock is not here. */
val testClock = MonotonicClock { System.nanoTime() / 1_000_000 }

/** In-memory storage, so the stores are just their logic. */
class FakePreferences : Preferences {
    private val strings = mutableMapOf<String, String?>()
    override fun getString(key: String) = strings[key]
    override fun putString(key: String, value: String?) { strings[key] = value }
}

class RecordingChannelSync : ChannelSync {
    /** Appended from an IO collector, read from the test thread. */
    val synced: MutableList<Pair<List<Group>, Map<String, RoomTile>>> =
        java.util.Collections.synchronizedList(mutableListOf())
    override fun sync(groups: List<Group>, tiles: Map<String, RoomTile>) {
        synced += groups to tiles
    }
}

/** Records what the system would have been told, and can drive the controls back. */
class RecordingNowPlaying : NowPlayingPublisher {
    data class Metadata(val title: String?, val artist: String?, val album: String?, val duration: Long, val artUrl: String? = null)
    data class State(
        val playing: Boolean,
        val idle: Boolean,
        val positionMillis: Long,
        val actions: PlaybackActions = PlaybackActions(),
    )

    val metadata = mutableListOf<Metadata>()
    val states = mutableListOf<State>()
    var controls: NowPlayingPublisher.Controls? = null
        private set
    var detached = false
        private set

    override fun attach(controls: NowPlayingPublisher.Controls) { this.controls = controls }
    override fun publish(title: String?, artist: String?, album: String?, durationMillis: Long, artUrl: String?) {
        metadata += Metadata(title, artist, album, durationMillis, artUrl)
    }
    override fun publishState(playing: Boolean, idle: Boolean, positionMillis: Long, actions: PlaybackActions) {
        states += State(playing, idle, positionMillis, actions)
    }

    /** Whether a media session is being advertised at all; false on a soundbar's TV input. */
    var presenting: Boolean = true
        private set

    override fun setPresenting(presenting: Boolean) { this.presenting = presenting }
    override fun detach(controls: NowPlayingPublisher.Controls) {
        if (this.controls !== controls) return
        detached = true
        this.controls = null
    }
}
