package com.rahga.x2rock.viewmodel

import com.rahga.x2rock.channel.ChannelSync
import com.rahga.x2rock.media.NowPlayingPublisher
import com.rahga.x2rock.model.Group
import com.rahga.x2rock.model.Track
import com.rahga.x2rock.store.Preferences

/** In-memory storage, so the stores are just their logic. */
class FakePreferences : Preferences {
    private val strings = mutableMapOf<String, String?>()
    private val sets = mutableMapOf<String, Set<String>>()
    override fun getString(key: String) = strings[key]
    override fun putString(key: String, value: String?) { strings[key] = value }
    override fun getStringSet(key: String) = sets[key] ?: emptySet()
    override fun putStringSet(key: String, value: Set<String>) { sets[key] = value }
}

class RecordingChannelSync : ChannelSync {
    val synced = mutableListOf<Pair<List<Group>, Map<String, Track?>>>()
    override fun sync(groups: List<Group>, nowPlaying: Map<String, Track?>) {
        synced += groups to nowPlaying
    }
}

/** Records what the system would have been told, and can drive the controls back. */
class RecordingNowPlaying : NowPlayingPublisher {
    data class Metadata(val title: String?, val artist: String?, val album: String?, val duration: Long)
    data class State(val playing: Boolean, val hasTrack: Boolean, val positionMillis: Long)

    val metadata = mutableListOf<Metadata>()
    val states = mutableListOf<State>()
    var controls: NowPlayingPublisher.Controls? = null
        private set
    var released = false
        private set

    override fun attach(controls: NowPlayingPublisher.Controls) { this.controls = controls }
    override fun publish(title: String?, artist: String?, album: String?, durationMillis: Long) {
        metadata += Metadata(title, artist, album, durationMillis)
    }
    override fun publishState(playing: Boolean, hasTrack: Boolean, positionMillis: Long) {
        states += State(playing, hasTrack, positionMillis)
    }
    override fun release() { released = true }
}
