package com.rahga.x2rock.cli.mpris

import com.rahga.x2rock.model.PlaybackStates
import com.rahga.x2rock.model.RepeatModes
import com.rahga.x2rock.model.Track
import com.rahga.x2rock.model.isPlaying
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.types.Variant

/** One poll's view of a room, plus when it was taken so Position can be extrapolated between polls. */
data class PlayerSnapshot(
    val roomName: String,
    val playbackState: String,
    val track: Track?,
    val positionMillis: Long,
    val volume: Int,
    val muted: Boolean,
    val shuffle: Boolean,
    val repeat: String,
    val capturedAtMillis: Long
) {
    companion object {
        fun idle(roomName: String, now: Long) = PlayerSnapshot(
            roomName, PlaybackStates.IDLE, null, 0L, 0, false, false, RepeatModes.NONE, now
        )
    }
}

const val MPRIS_PATH = "/org/mpris/MediaPlayer2"
const val MPRIS_ROOT_IFACE = "org.mpris.MediaPlayer2"
const val MPRIS_PLAYER_IFACE = "org.mpris.MediaPlayer2.Player"

/** MPRIS wants "Playing" | "Paused" | "Stopped"; Sonos's BUFFERING is on its way to Playing. */
fun mprisPlaybackStatus(state: String): String = when (state) {
    PlaybackStates.PLAYING, PlaybackStates.BUFFERING -> "Playing"
    PlaybackStates.PAUSED -> "Paused"
    else -> "Stopped"
}

fun mprisLoopStatus(repeat: String): String = when (repeat) {
    RepeatModes.ALL -> "Playlist"
    RepeatModes.ONE -> "Track"
    else -> "None"
}

fun sonosRepeat(loopStatus: String): String = when (loopStatus) {
    "Playlist" -> RepeatModes.ALL
    "Track" -> RepeatModes.ONE
    else -> RepeatModes.NONE
}

/**
 * Position as MPRIS wants it: microseconds, extrapolated from the last poll when playing so
 * progress bars move between polls instead of stepping every five seconds. Capped at the
 * track length so a stale snapshot never reports past the end.
 */
fun mprisPositionMicros(s: PlayerSnapshot, nowMillis: Long): Long {
    val elapsed = if (s.playbackState.isPlaying()) (nowMillis - s.capturedAtMillis).coerceAtLeast(0) else 0L
    val pos = s.positionMillis + elapsed
    val capped = s.track?.durationMillis?.takeIf { it > 0 }?.let { minOf(pos, it) } ?: pos
    return capped * 1_000
}

/** A stable-per-track object path; MPRIS requires one even though Sonos gives us none. */
fun mprisTrackId(track: Track?): DBusPath {
    if (track?.name == null) return DBusPath("/org/mpris/MediaPlayer2/TrackList/NoTrack")
    val hash = Integer.toHexString((track.name + " " + track.artist?.name + " " + track.album?.name).hashCode())
    return DBusPath("/com/rahga/x2rock/track/$hash")
}

fun mprisMetadata(track: Track?): Map<String, Variant<*>> = buildMap {
    put("mpris:trackid", Variant(mprisTrackId(track)))
    val t = track ?: return@buildMap
    t.durationMillis.takeIf { it > 0 }?.let { put("mpris:length", Variant(it * 1_000)) }
    t.name?.let { put("xesam:title", Variant(it)) }
    t.artist?.name?.takeIf { it.isNotBlank() }?.let { put("xesam:artist", Variant(listOf(it), "as")) }
    t.album?.name?.takeIf { it.isNotBlank() }?.let { put("xesam:album", Variant(it)) }
    t.imageUrl?.takeIf { it.isNotBlank() }?.let { put("mpris:artUrl", Variant(it)) }
}

/** MPRIS volume is 0.0–1.0; muted reports as 0 so bar widgets show the truth. */
fun mprisVolume(s: PlayerSnapshot): Double = if (s.muted) 0.0 else s.volume / 100.0

fun sonosVolume(mpris: Double): Int = Math.round(mpris * 100).toInt().coerceIn(0, 100)

fun rootProperties(identity: String): Map<String, Variant<*>> = mapOf(
    "CanQuit" to Variant(true),
    "CanRaise" to Variant(false),
    "HasTrackList" to Variant(false),
    "Identity" to Variant(identity),
    "DesktopEntry" to Variant("x2rocktv"),
    "SupportedUriSchemes" to Variant(emptyList<String>(), "as"),
    "SupportedMimeTypes" to Variant(emptyList<String>(), "as")
)

/** Everything except Position, which is read live rather than signalled. */
fun playerProperties(s: PlayerSnapshot): Map<String, Variant<*>> {
    val hasTrack = s.track?.name != null
    return mapOf(
        "PlaybackStatus" to Variant(mprisPlaybackStatus(s.playbackState)),
        "LoopStatus" to Variant(mprisLoopStatus(s.repeat)),
        "Rate" to Variant(1.0),
        "Shuffle" to Variant(s.shuffle),
        "Metadata" to Variant(mprisMetadata(s.track), "a{sv}"),
        "Volume" to Variant(mprisVolume(s)),
        "MinimumRate" to Variant(1.0),
        "MaximumRate" to Variant(1.0),
        "CanGoNext" to Variant(hasTrack),
        "CanGoPrevious" to Variant(hasTrack),
        "CanPlay" to Variant(hasTrack),
        "CanPause" to Variant(hasTrack),
        "CanSeek" to Variant(hasTrack && (s.track?.durationMillis ?: 0) > 0),
        "CanControl" to Variant(true)
    )
}

/** Which player properties differ between two snapshots — the PropertiesChanged payload. */
fun changedPlayerProperties(old: PlayerSnapshot?, new: PlayerSnapshot): Map<String, Variant<*>> {
    val after = playerProperties(new)
    if (old == null) return after
    val before = playerProperties(old)
    return after.filter { (k, v) -> before[k]?.value != v.value }
}
