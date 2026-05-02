package com.rahga.x2rock.repository

import com.rahga.x2rock.model.DeleteQueueItemsRequest
import com.rahga.x2rock.model.FavoritesResponse
import com.rahga.x2rock.model.Group
import com.rahga.x2rock.model.GroupVolume
import com.rahga.x2rock.model.LoadFavoriteRequest
import com.rahga.x2rock.model.ModifyGroupMembersRequest
import com.rahga.x2rock.model.PlayModeResponse
import com.rahga.x2rock.model.PlayModeState
import com.rahga.x2rock.model.PlaybackMetadata
import com.rahga.x2rock.model.PlaybackState
import com.rahga.x2rock.model.Player
import com.rahga.x2rock.model.QueueResponse
import com.rahga.x2rock.model.SeekRequest
import com.rahga.x2rock.model.SetMuteRequest
import com.rahga.x2rock.model.SetPlayModeRequest
import com.rahga.x2rock.model.SetVolumeRequest
import com.rahga.x2rock.network.SonosApiService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.ResponseBody
import retrofit2.HttpException
import retrofit2.Response
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SonosRepository @Inject constructor(
    private val apiService: SonosApiService,
    private val authRepository: SonosAuthRepository
) {
    @Volatile private var cachedHouseholdId: String? = null
    @Volatile private var cachedPlayers: Map<String, Player> = emptyMap()
    @Volatile private var cachedGroups: Map<String, Group> = emptyMap()

    suspend fun getGroups(): Result<List<Group>> = withContext(Dispatchers.IO) {
        runCatching {
            val householdId = cachedHouseholdId
                ?: fetchWithRefresh { apiService.getHouseholds() }
                    .households
                    .firstOrNull()
                    ?.id
                    ?.also { cachedHouseholdId = it }
                ?: error("No households found on this account")

            val response = fetchWithRefresh { apiService.getGroups(householdId) }
            cachedPlayers = response.players.associateBy { it.id }
            cachedGroups = response.groups.associateBy { it.id }
            response.groups
        }
    }

    fun getPlayerIdsForGroup(groupId: String): List<String> =
        cachedGroups[groupId]?.playerIds ?: emptyList()

    fun getPlayerName(playerId: String): String =
        cachedPlayers[playerId]?.name ?: playerId

    suspend fun getPlaybackState(groupId: String): Result<PlaybackState> = withContext(Dispatchers.IO) {
        runCatching { fetchWithRefresh { apiService.getPlaybackState(groupId) } }
    }

    suspend fun getPlaybackMetadata(groupId: String): Result<PlaybackMetadata?> = withContext(Dispatchers.IO) {
        runCatching {
            try {
                fetchWithRefresh { apiService.getPlaybackMetadata(groupId) }
            } catch (e: HttpException) {
                if (e.code() == 404) null else throw e
            }
        }
    }

    suspend fun getPlayMode(groupId: String): Result<PlayModeResponse> = withContext(Dispatchers.IO) {
        runCatching { fetchWithRefresh { apiService.getPlayMode(groupId) } }
    }

    suspend fun setPlayMode(groupId: String, playMode: PlayModeState): Result<PlayModeResponse> =
        withContext(Dispatchers.IO) {
            runCatching { fetchWithRefresh { apiService.setPlayMode(groupId, SetPlayModeRequest(playMode)) } }
        }

    suspend fun getQueue(groupId: String): Result<QueueResponse> = withContext(Dispatchers.IO) {
        runCatching { fetchWithRefresh { apiService.getQueue(groupId) } }
    }

    suspend fun deleteQueueItems(groupId: String, ids: List<String>): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { executeWithRefresh { apiService.deleteQueueItems(groupId, DeleteQueueItemsRequest(ids)) } }
    }

    suspend fun togglePlayPause(groupId: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { executeWithRefresh { apiService.togglePlayPause(groupId, emptyMap()) } }
    }

    suspend fun skipToNextTrack(groupId: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { executeWithRefresh { apiService.skipToNextTrack(groupId, emptyMap()) } }
    }

    suspend fun skipToPreviousTrack(groupId: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { executeWithRefresh { apiService.skipToPreviousTrack(groupId, emptyMap()) } }
    }

    suspend fun getGroupVolume(groupId: String): Result<GroupVolume> = withContext(Dispatchers.IO) {
        runCatching { fetchWithRefresh { apiService.getGroupVolume(groupId) } }
    }

    suspend fun setGroupVolume(groupId: String, volume: Int): Result<GroupVolume> = withContext(Dispatchers.IO) {
        runCatching { fetchWithRefresh { apiService.setGroupVolume(groupId, SetVolumeRequest(volume)) } }
    }

    suspend fun setGroupMute(groupId: String, muted: Boolean): Result<GroupVolume> = withContext(Dispatchers.IO) {
        runCatching { fetchWithRefresh { apiService.setGroupMute(groupId, SetMuteRequest(muted)) } }
    }

    suspend fun seek(groupId: String, positionMillis: Long): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { executeWithRefresh { apiService.seek(groupId, SeekRequest(positionMillis)) } }
    }

    suspend fun skipToQueueItem(groupId: String, trackNumber: Int): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { executeWithRefresh { apiService.seek(groupId, SeekRequest(positionMillis = 0, trackNumber = trackNumber)) } }
    }

    suspend fun getPlayerVolume(playerId: String): Result<GroupVolume> = withContext(Dispatchers.IO) {
        runCatching { fetchWithRefresh { apiService.getPlayerVolume(playerId) } }
    }

    suspend fun setPlayerVolume(playerId: String, volume: Int): Result<GroupVolume> = withContext(Dispatchers.IO) {
        runCatching { fetchWithRefresh { apiService.setPlayerVolume(playerId, SetVolumeRequest(volume)) } }
    }

    suspend fun setPlayerMute(playerId: String, muted: Boolean): Result<GroupVolume> = withContext(Dispatchers.IO) {
        runCatching { fetchWithRefresh { apiService.setPlayerMute(playerId, SetMuteRequest(muted)) } }
    }

    suspend fun joinGroup(sourceGroup: Group, targetGroupId: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { modifyGroupMembers(targetGroupId, ModifyGroupMembersRequest(playerIdsToAdd = sourceGroup.playerIds)) }
    }

    suspend fun soloGroup(group: Group): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val toRemove = group.playerIds.filter { it != group.coordinatorId }
            if (toRemove.isEmpty()) return@runCatching
            modifyGroupMembers(group.id, ModifyGroupMembersRequest(playerIdsToRemove = toRemove))
        }
    }

    suspend fun partyMode(topGroup: Group, otherGroups: List<Group>): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val playerIdsToAdd = otherGroups.flatMap { it.playerIds }
            if (playerIdsToAdd.isEmpty()) return@runCatching
            modifyGroupMembers(topGroup.id, ModifyGroupMembersRequest(playerIdsToAdd = playerIdsToAdd))
        }
    }

    private suspend fun modifyGroupMembers(groupId: String, request: ModifyGroupMembersRequest) {
        val householdId = cachedHouseholdId ?: error("No household cached")
        executeWithRefresh { apiService.modifyGroupMembers(householdId, groupId, request) }
    }

    suspend fun getFavorites(): Result<FavoritesResponse> = withContext(Dispatchers.IO) {
        runCatching {
            val householdId = cachedHouseholdId ?: error("No household cached — call getGroups first")
            fetchWithRefresh { apiService.getFavorites(householdId) }
        }
    }

    suspend fun loadFavorite(groupId: String, favoriteId: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { executeWithRefresh { apiService.loadFavorite(groupId, LoadFavoriteRequest(favoriteId)) } }
    }

    private suspend fun <T> fetchWithRefresh(call: suspend () -> Response<T>): T {
        authRepository.ensureValidToken()
        val response = call()
        if (response.code() == 401) {
            authRepository.refreshAccessToken().getOrThrow()
            val retried = call()
            if (!retried.isSuccessful) throw HttpException(retried)
            return retried.body() ?: error("Empty response body after token refresh")
        }
        if (!response.isSuccessful) throw HttpException(response)
        return response.body() ?: error("Empty response body")
    }

    private suspend fun executeWithRefresh(call: suspend () -> Response<ResponseBody>) {
        authRepository.ensureValidToken()
        val response = call()
        response.body()?.close()
        if (response.code() == 401) {
            authRepository.refreshAccessToken().getOrThrow()
            val retried = call()
            retried.body()?.close()
            if (!retried.isSuccessful) throw HttpException(retried)
            return
        }
        if (!response.isSuccessful) throw HttpException(response)
    }
}
