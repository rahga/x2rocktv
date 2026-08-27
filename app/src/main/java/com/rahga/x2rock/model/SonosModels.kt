package com.rahga.x2rock.model

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
    val playbackState: String
)

data class Player(
    val id: String,
    val name: String
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
    val durationMillis: Long = 0
)
data class CurrentItem(val track: Track?)
data class ContainerMetadata(val name: String? = null)
data class PlaybackMetadata(val currentItem: CurrentItem? = null, val container: ContainerMetadata? = null)

data class GroupVolume(
    val volume: Int,
    val muted: Boolean,
    val fixed: Boolean
)

data class SetVolumeRequest(val volume: Int)
data class SetMuteRequest(val muted: Boolean)
data class SeekRequest(val positionMillis: Long, val trackNumber: Int? = null)

data class PlayModeState(
    val repeat: String = "REPEAT_NONE",
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

fun String.isPlaying() = this == "PLAYBACK_STATE_PLAYING"

/** False only when the group is idle, i.e. there is no track worth asking the API about. */
fun String.hasLoadedContent() = this != "PLAYBACK_STATE_IDLE"
