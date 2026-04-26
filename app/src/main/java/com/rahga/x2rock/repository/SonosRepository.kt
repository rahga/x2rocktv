package com.rahga.x2rock.repository

import com.rahga.x2rock.model.Group
import com.rahga.x2rock.model.GroupVolume
import com.rahga.x2rock.model.PlaybackMetadata
import com.rahga.x2rock.model.PlaybackState
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
    suspend fun getGroups(): Result<List<Group>> = withContext(Dispatchers.IO) {
        runCatching {
            val householdId = fetchWithRefresh { apiService.getHouseholds() }
                .households
                .firstOrNull()
                ?.id
                ?: error("No households found on this account")

            fetchWithRefresh { apiService.getGroups(householdId) }.groups
        }
    }

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

    private suspend fun <T> fetchWithRefresh(call: suspend () -> Response<T>): T {
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
