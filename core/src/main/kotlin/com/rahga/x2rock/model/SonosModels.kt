package com.rahga.x2rock.model

import com.google.gson.annotations.SerializedName

data class Household(
    val id: String,
    val name: String
)

data class HouseholdsResponse(
    val households: List<Household>
)

data class Group(
    val id: String,
    val name: String,
    val coordinatorId: String,
    val playerIds: List<String>,
    /**
     * Nullable because the wire really does omit it: a `groups:1` *subscribe event* carries
     * no `playbackState`, though the `getGroups` *reply* does. Declaring it non-null let
     * Gson write null straight past the Kotlin type and crash the first recomposition after
     * a group formed. `SonosHousehold` fills it in, so consumers normally see a value.
     */
    val playbackState: String? = null
)

data class Player(
    val id: String,
    val name: String,
    /**
     * What this speaker can do, e.g. `PLAYBACK`, `AIRPLAY`, `HT_PLAYBACK`. The last is how
     * a soundbar is told apart from a speaker — a room has a TV input to offer when any of
     * its members reports it, which need not be the one coordinating the group.
     */
    val capabilities: List<String> = emptyList(),
    /**
     * `wss://<ip>:1443/websocket/api`, and so the only place a player's address is
     * reported. Present over the LAN transport, absent (null) over the cloud one.
     */
    val websocketUrl: String? = null
)

data class GroupsResponse(
    val groups: List<Group>,
    val players: List<Player>
)

data class PlaybackState(
    val playbackState: String,
    val positionMillis: Long = 0
)

data class TrackArtist(val name: String?)
data class TrackAlbum(val name: String?)
data class Track(
    val name: String?,
    val artist: TrackArtist?,
    val album: TrackAlbum?,
    val imageUrl: String?,
    val durationMillis: Long = 0,
    /** Alternate art for the same track; `imageUrl` is the one to prefer. */
    val images: List<SonosImage> = emptyList()
)
data class CurrentItem(val track: Track?)
data class SonosImage(val url: String? = null)

/**
 * What a soundbar is receiving over HDMI, which is not what the source claims to send: a TV
 * that has quietly dropped to stereo still reports its codec with two channels and no LFE.
 */
data class HomeTheaterFormat(
    val numGroundChannels: Int = 0,
    /**
     * The player spells this `numLFEChannels`. That is not the casing a camelCase
     * convention derives from the field name, so it has to be named exactly.
     */
    @SerializedName("numLFEChannels") val numLfeChannels: Int = 0,
    val numHeightChannels: Int = 0,
    /** The codec: `Dolby Digital`, `PCM`, `Silence`, `No Signal`. */
    val streamDescription: String? = null,
) {
    /** The layout as people write it: `2.0`, `5.1`, `5.1.2`. */
    val channels: String
        get() = "$numGroundChannels.$numLfeChannels" +
            if (numHeightChannels > 0) ".$numHeightChannels" else ""

    /** True when nothing is arriving at all — the television off, or between sources. */
    val isSilent: Boolean
        get() = numGroundChannels == 0 && numLfeChannels == 0 && numHeightChannels == 0

    val isSurround: Boolean
        get() = numGroundChannels > 2 || numLfeChannels > 0 || numHeightChannels > 0

    /**
     * Codec and layout together — the pair that shows a source has fallen back, "Dolby
     * Digital 2.0" rather than "Dolby Digital 5.1".
     *
     * With the television off a player reports `No Signal` and no channels at all, and
     * "No Signal 0.0" reads worse than "No Signal", so a silent input names only its codec.
     */
    fun summary(): String {
        val codec = streamDescription?.takeIf { it.isNotEmpty() }
        return when {
            codec != null && isSilent -> codec
            codec != null -> "$codec $channels"
            isSilent -> ""
            else -> channels
        }
    }
}

/**
 * The two soundbar settings the Sonos app puts on its TV screen: Night Sound and Speech
 * Enhancement.
 *
 * `playerVolume:1` does not carry them and no event announces them, so they are read from
 * `settings:1 getPlayerSettings` and nowhere else. Read-only over the Control API — writing
 * answers `ERROR_NO_PERMISSION` and goes over UPnP instead; see `Upnp.setEq`.
 *
 * The block is present on every player and inert on anything without an HDMI socket, so it
 * is worth reading only for a soundbar.
 */
data class HomeTheaterOptions(
    val nightMode: Boolean = false,
    val enhanceDialog: Boolean = false,
    /**
     * The strength behind [enhanceDialog], which the Sonos app itself shows only as on or
     * off.
     *
     * Read and reported, but not written back. `DialogLevel` is documented as 1-4 on devices
     * that grade it, and on newer ones the on/off half moves to a separate
     * `SpeechEnhanceEnabled`; a Beam does neither, reading level 1 while enhancement is on,
     * which is why 0/1 is the right write *for this hardware* and the toggle is honest here.
     * Widening it would need a device that grades the level, which is not one we have — so
     * the field is kept visible rather than acted on. See `x2rock`'s `docs/architecture.md`,
     * `DialogLevel` section, which is the more careful statement of this.
     */
    val enhanceDialogLevel: Int = 0,
)

/**
 * `settings:1 getPlayerSettings` — player-scoped, and answered without an account, unlike
 * `getSettings`, which wants a `userId` this account-less model has no way to supply.
 *
 * Only the home-theatre block is read; the object carries more (room name, volume mode,
 * spatial audio) that nothing here has a use for.
 */
data class PlayerSettings(val homeTheater: HomeTheaterOptions? = null)

data class ContainerMetadata(
    val name: String? = null,
    /** e.g. `album`, `station`, `linein.homeTheater.hdmi`. */
    val type: String? = null,
    val imageUrl: String? = null,
    /** Alternate art for the same container; `imageUrl` is the one to prefer. */
    val images: List<SonosImage> = emptyList(),
    /** Present only while a soundbar is on its TV input — its presence *is* the signal. */
    val htInputFormat: HomeTheaterFormat? = null,
)
/**
 * What the *source* permits, which is not a property of the speaker: a queue of tracks allows
 * skip, seek and shuffle, while a live stream refuses all three — and refuses pause too,
 * offering only stop. Verified on hardware: pausing an internet radio stream leaves the room
 * IDLE rather than PAUSED, so `canPause: false` is a statement of fact and not a hint.
 *
 * Defaults are permissive so that a payload without the block leaves every control drawn,
 * which is what the app did before it read this at all.
 */
data class PlaybackActions(
    val canPlay: Boolean = true,
    val canPause: Boolean = true,
    val canStop: Boolean = false,
    val canSkip: Boolean = true,
    val canSkipToPrevious: Boolean = true,
    val canSeek: Boolean = true,
    val canRepeat: Boolean = true,
    val canShuffle: Boolean = true,
    val canCrossfade: Boolean = true,
)

data class PlaybackMetadata(
    val currentItem: CurrentItem? = null,
    val container: ContainerMetadata? = null,
    /**
     * What the station says is on right now — and the only now-playing a service-less stream
     * has. A stream loaded by URL carries no `currentItem` and no track object at all, so
     * without this a room playing internet radio can say nothing but its own state.
     *
     * Deliberately left whole. "Artist - Title" is an Icecast convention rather than a
     * format — stations put a show name, a slogan, or nothing in the same field — so
     * splitting it on a hyphen would invent an artist wherever the guess happened to fit.
     */
    val streamInfo: String? = null,
)

data class GroupVolume(
    val volume: Int,
    val muted: Boolean,
    val fixed: Boolean
)

data class SetVolumeRequest(val volume: Int)
data class SetMuteRequest(val muted: Boolean)
data class SeekRequest(val positionMillis: Long, val trackNumber: Int? = null)

data class PlayModeState(
    val repeat: String = RepeatModes.NONE,
    val shuffle: Boolean = false,
    val crossfade: Boolean = false
)
data class PlayModeResponse(val playMode: PlayModeState)
data class SetPlayModeRequest(val playMode: PlayModeState)

data class QueueItem(
    val id: String = "",
    val track: Track? = null,
    val deleted: Boolean = false
)
data class QueueResponse(
    val items: List<QueueItem> = emptyList(),
    val totalItems: Int = 0
)

data class Favorite(
    val id: String,
    val name: String,
    val description: String? = null,
    val imageUrl: String? = null
)
data class FavoritesResponse(
    val items: List<Favorite> = emptyList(),
    val totalItems: Int = 0
)
data class LoadFavoriteRequest(
    val favoriteId: String,
    val playOnCompletion: Boolean = true
)

data class ModifyGroupMembersRequest(
    val playerIdsToAdd: List<String> = emptyList(),
    val playerIdsToRemove: List<String> = emptyList()
)

data class DeleteQueueItemsRequest(val ids: List<String>)

object PlaybackStates {
    const val IDLE = "PLAYBACK_STATE_IDLE"
    const val BUFFERING = "PLAYBACK_STATE_BUFFERING"
    const val PAUSED = "PLAYBACK_STATE_PAUSED"
    const val PLAYING = "PLAYBACK_STATE_PLAYING"
}

object RepeatModes {
    const val NONE = "REPEAT_NONE"
    const val ALL = "REPEAT_ALL"
    const val ONE = "REPEAT_ONE"
}

fun String.isPlaying() = this == PlaybackStates.PLAYING

/** False only when the group is idle, i.e. there is no track worth asking the API about. */
fun String.hasLoadedContent() = this != PlaybackStates.IDLE

fun String.toPlaybackLabel(): String = when (this) {
    PlaybackStates.PLAYING -> "Playing"
    PlaybackStates.PAUSED -> "Paused"
    PlaybackStates.BUFFERING -> "Buffering"
    else -> "Idle"
}
