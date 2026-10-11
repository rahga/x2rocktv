package com.rahga.x2rock

import com.rahga.x2rock.channel.ChannelSync
import com.rahga.x2rock.channel.RoomTile
import com.rahga.x2rock.media.NowPlayingPublisher
import com.rahga.x2rock.model.Group
import com.rahga.x2rock.model.PlaybackActions
import com.rahga.x2rock.store.Preferences
import com.rahga.x2rock.viewmodel.MonotonicClock

// The on-device counterparts of the JVM tests' Fakes.kt: just enough to build the view models by
// hand. Nothing here records; the focus tests read the screen, not these.

/** In-memory storage, so the stores are just their logic and nothing touches the app's own prefs. */
class TestPreferences : Preferences {
    private val strings = mutableMapOf<String, String?>()
    override fun getString(key: String) = strings[key]
    override fun putString(key: String, value: String?) { strings[key] = value }
}

/** No launcher channel: the test is not the launcher's business. */
object NoChannelSync : ChannelSync {
    override fun sync(groups: List<Group>, tiles: Map<String, RoomTile>) = Unit
}

/** No media session: a test must not advertise one on the device it runs on. */
object SilentNowPlaying : NowPlayingPublisher {
    override fun attach(controls: NowPlayingPublisher.Controls) = Unit
    override fun publish(title: String?, artist: String?, album: String?, durationMillis: Long, artUrl: String?) = Unit
    override fun publishState(playing: Boolean, idle: Boolean, positionMillis: Long, actions: PlaybackActions) = Unit
    override fun setPresenting(presenting: Boolean) = Unit
    override fun detach(controls: NowPlayingPublisher.Controls) = Unit
}

val testClock = MonotonicClock { System.nanoTime() / 1_000_000 }
