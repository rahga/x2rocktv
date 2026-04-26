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
    val imageUrl: String?
)
data class CurrentItem(val track: Track?)
data class PlaybackMetadata(val currentItem: CurrentItem?)

data class GroupVolume(
    val volume: Int,
    val muted: Boolean,
    val fixed: Boolean
)

data class SetVolumeRequest(val volume: Int)
