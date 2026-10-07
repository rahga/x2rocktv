package com.rahga.x2rock.viewmodel

import com.rahga.x2rock.model.PlaybackStates

/**
 * What a room is doing, in one of four words — the single rule the room list, the player pane and
 * the room panel all use, because they used to disagree: a radio station paused in Kitchen read
 * "Smooth Jazz" in the list, "Smooth Jazz" in the pane and "Kitchen · Idle" in the panel.
 *
 * "Idle" was the transport's word and said nothing a listener could use. A room with something
 * loaded and not playing is **Paused** when the player paused it and **Stopped** when it could
 * only stop — a live stream, which a pause leaves IDLE (verified on hardware), and whose button
 * already reads "Stop". A room with nothing loaded at all says so.
 */
enum class RoomActivity(val label: String) {
    PLAYING("Playing"),
    PAUSED("Paused"),
    STOPPED("Stopped"),
    EMPTY("Nothing playing");

    val isPlaying: Boolean get() = this == PLAYING
}

fun roomActivity(playbackState: String?, hasSource: Boolean): RoomActivity = when (playbackState) {
    PlaybackStates.PLAYING, PlaybackStates.BUFFERING -> RoomActivity.PLAYING
    PlaybackStates.PAUSED -> RoomActivity.PAUSED
    else -> if (hasSource) RoomActivity.STOPPED else RoomActivity.EMPTY
}

/** Whether the room list's row has anything loaded, by what the player has told us about it. */
val HomeViewModel.RoomInfo.hasSource: Boolean
    get() = onTvInput || isRadio || track?.name != null || source != null || streamInfo != null

/** The same question for the player pane, by what its state carries. */
val PlayerUiState.hasSource: Boolean
    get() = onTvInput || isRadio || trackName != null || streamInfo != null || sourceName != null

/**
 * A group's name as a listener reads it: Sonos names a group by its coordinator, and "+ 2" says it
 * is more than one room. The room list and the room panel both draw it, so they cannot disagree.
 */
val com.rahga.x2rock.model.Group.label: String
    get() = if (playerIds.size > 1 && " + " !in name) "$name + ${playerIds.size - 1}" else name

/**
 * What a piece of content is, in a word, from its Sonos or service type — one table, so a track
 * reads the same in Recently played as in Search. `null` where the type says nothing useful.
 */
fun kindLabel(type: String): String? = when (type) {
    "track" -> "Track"
    "album" -> "Album"
    "artist" -> "Artist"
    "playlist", "albumList", "trackList" -> "Playlist"
    "stream", "program" -> "Radio"
    "audiobook" -> "Audiobook"
    "show", "podcast" -> "Podcast"
    else -> null
}
