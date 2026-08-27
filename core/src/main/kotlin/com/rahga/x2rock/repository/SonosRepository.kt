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
import com.rahga.x2rock.model.Track
import com.rahga.x2rock.model.hasLoadedContent
import com.rahga.x2rock.network.RateLimitedException
import com.rahga.x2rock.network.SonosApiService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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

    /** Household to use when the caller hasn't pinned one — set via config/`--household`. */
    var preferredHouseholdId: String? = null

    /**
     * Every household on the account, each with the names of the players inside it. Sonos
     * accounts can span multiple households (e.g. separate physical locations); the API gives
     * households no name of their own, so their player list is the only way to tell them apart.
     */
    suspend fun listHouseholds(): Result<Map<String, List<String>>> = apiCall {
        val households = fetchWithRefresh { apiService.getHouseholds() }.households
        households.associate { household ->
            val players = fetchWithRefresh { apiService.getGroups(household.id) }.players.map { it.name }
            household.id to players
        }
    }

    suspend fun getGroups(): Result<List<Group>> = apiCall {
        val householdId = cachedHouseholdId
            ?: preferredHouseholdId
            ?: fetchWithRefresh { apiService.getHouseholds() }
                .households
                .firstOrNull()
                ?.id
            ?: error("No households found on this account")
        cachedHouseholdId = householdId

        val response = fetchWithRefresh { apiService.getGroups(householdId) }
        cachedPlayers = response.players.associateBy { it.id }
        cachedGroups = response.groups.associateBy { it.id }
        response.groups
    }

    fun getPlayerIdsForGroup(groupId: String): List<String> =
        cachedGroups[groupId]?.playerIds ?: emptyList()

    fun getPlayerName(playerId: String): String =
        cachedPlayers[playerId]?.name ?: playerId

    suspend fun getPlaybackState(groupId: String): Result<PlaybackState> = apiCall {
        fetchWithRefresh { apiService.getPlaybackState(groupId) }
    }

    suspend fun getPlaybackMetadata(groupId: String): Result<PlaybackMetadata?> = apiCall {
        try {
            fetchWithRefresh { apiService.getPlaybackMetadata(groupId) }
        } catch (e: HttpException) {
            if (e.code() == 404) null else throw e
        }
    }

    /**
     * Now-playing track per group. Idle groups report null without a request — they have no track,
     * and the groups response already said so, which saves one call per idle room per poll.
     */
    suspend fun getNowPlaying(groups: List<Group>): Map<String, Track?> = coroutineScope {
        val (loaded, idle) = groups.partition { it.playbackState.hasLoadedContent() }
        val fetched = loaded.map { group ->
            async { group.id to getPlaybackMetadata(group.id).getOrNull()?.currentItem?.track }
        }.awaitAll()
        fetched.toMap() + idle.associate { it.id to null }
    }

    suspend fun getPlayMode(groupId: String): Result<PlayModeResponse> = apiCall {
        fetchWithRefresh { apiService.getPlayMode(groupId) }
    }

    suspend fun setPlayMode(groupId: String, playMode: PlayModeState): Result<PlayModeResponse> = apiCall {
        fetchWithRefresh { apiService.setPlayMode(groupId, SetPlayModeRequest(playMode)) }
    }

    suspend fun getQueue(groupId: String): Result<QueueResponse> = apiCall {
        fetchWithRefresh { apiService.getQueue(groupId) }
    }

    suspend fun deleteQueueItems(groupId: String, ids: List<String>): Result<Unit> = apiCall {
        executeWithRefresh { apiService.deleteQueueItems(groupId, DeleteQueueItemsRequest(ids)) }
    }

    suspend fun togglePlayPause(groupId: String): Result<Unit> = apiCall {
        executeWithRefresh { apiService.togglePlayPause(groupId, emptyMap()) }
    }

    suspend fun skipToNextTrack(groupId: String): Result<Unit> = apiCall {
        executeWithRefresh { apiService.skipToNextTrack(groupId, emptyMap()) }
    }

    suspend fun skipToPreviousTrack(groupId: String): Result<Unit> = apiCall {
        executeWithRefresh { apiService.skipToPreviousTrack(groupId, emptyMap()) }
    }

    suspend fun getGroupVolume(groupId: String): Result<GroupVolume> = apiCall {
        fetchWithRefresh { apiService.getGroupVolume(groupId) }
    }

    suspend fun setGroupVolume(groupId: String, volume: Int): Result<GroupVolume> = apiCall {
        fetchWithRefresh { apiService.setGroupVolume(groupId, SetVolumeRequest(volume)) }
    }

    suspend fun setGroupMute(groupId: String, muted: Boolean): Result<GroupVolume> = apiCall {
        fetchWithRefresh { apiService.setGroupMute(groupId, SetMuteRequest(muted)) }
    }

    suspend fun seek(groupId: String, positionMillis: Long): Result<Unit> = apiCall {
        executeWithRefresh { apiService.seek(groupId, SeekRequest(positionMillis)) }
    }

    suspend fun skipToQueueItem(groupId: String, trackNumber: Int): Result<Unit> = apiCall {
        executeWithRefresh {
            apiService.seek(groupId, SeekRequest(positionMillis = 0, trackNumber = trackNumber))
        }
    }

    suspend fun getPlayerVolume(playerId: String): Result<GroupVolume> = apiCall {
        fetchWithRefresh { apiService.getPlayerVolume(playerId) }
    }

    suspend fun setPlayerVolume(playerId: String, volume: Int): Result<GroupVolume> = apiCall {
        fetchWithRefresh { apiService.setPlayerVolume(playerId, SetVolumeRequest(volume)) }
    }

    suspend fun setPlayerMute(playerId: String, muted: Boolean): Result<GroupVolume> = apiCall {
        fetchWithRefresh { apiService.setPlayerMute(playerId, SetMuteRequest(muted)) }
    }

    suspend fun joinGroup(sourceGroup: Group, targetGroupId: String): Result<Unit> = apiCall {
        modifyGroupMembers(targetGroupId, ModifyGroupMembersRequest(playerIdsToAdd = sourceGroup.playerIds))
    }

    suspend fun removePlayerFromGroup(groupId: String, playerId: String): Result<Unit> = apiCall {
        modifyGroupMembers(groupId, ModifyGroupMembersRequest(playerIdsToRemove = listOf(playerId)))
    }

    suspend fun soloGroup(group: Group): Result<Unit> = apiCall {
        val toRemove = group.playerIds.filter { it != group.coordinatorId }
        if (toRemove.isNotEmpty()) {
            modifyGroupMembers(group.id, ModifyGroupMembersRequest(playerIdsToRemove = toRemove))
        }
    }

    suspend fun partyMode(topGroup: Group, otherGroups: List<Group>): Result<Unit> = apiCall {
        val playerIdsToAdd = otherGroups.flatMap { it.playerIds }
        if (playerIdsToAdd.isNotEmpty()) {
            modifyGroupMembers(topGroup.id, ModifyGroupMembersRequest(playerIdsToAdd = playerIdsToAdd))
        }
    }

    private suspend fun modifyGroupMembers(groupId: String, request: ModifyGroupMembersRequest) {
        val householdId = cachedHouseholdId ?: error("No household cached")
        executeWithRefresh { apiService.modifyGroupMembers(householdId, groupId, request) }
    }

    suspend fun getFavorites(): Result<FavoritesResponse> = apiCall {
        val householdId = cachedHouseholdId ?: error("No household cached — call getGroups first")
        fetchWithRefresh { apiService.getFavorites(householdId) }
    }

    suspend fun loadFavorite(groupId: String, favoriteId: String): Result<Unit> = apiCall {
        executeWithRefresh { apiService.loadFavorite(groupId, LoadFavoriteRequest(favoriteId)) }
    }

    /**
     * Wraps a call in a [Result], deliberately letting [CancellationException] through —
     * runCatching would capture it and leave a cancelled poll looking like a failed request.
     */
    private suspend fun <T> apiCall(block: suspend () -> T): Result<T> = withContext(Dispatchers.IO) {
        try {
            Result.success(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    /**
     * Refreshes proactively, issues the call, and retries once on a 401. A 429 surfaces as
     * [RateLimitedException] so pollers can back off by the server's Retry-After.
     */
    private suspend fun <T> callWithRefresh(call: suspend () -> Response<T>): Response<T> {
        // Best effort: if the proactive refresh fails the token may still be inside its margin,
        // and the 401 path below is the real safety net.
        authRepository.ensureValidToken()

        var response = call()
        if (response.code() == 401) {
            response.errorBody()?.close()
            authRepository.refreshAccessToken().getOrThrow()
            response = call()
        }
        if (!response.isSuccessful) {
            val failure = if (response.code() == 429) {
                RateLimitedException(response.retryAfterMillis())
            } else {
                HttpException(response)
            }
            response.errorBody()?.close()
            throw failure
        }
        return response
    }

    private suspend fun <T> fetchWithRefresh(call: suspend () -> Response<T>): T =
        callWithRefresh(call).body() ?: error("Empty response body")

    private suspend fun executeWithRefresh(call: suspend () -> Response<ResponseBody>) {
        callWithRefresh(call).body()?.close()
    }

    private fun Response<*>.retryAfterMillis(): Long? =
        headers()["Retry-After"]?.toLongOrNull()?.times(1_000L)
}
