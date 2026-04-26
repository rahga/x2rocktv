package com.rahga.x2rock.repository

import com.rahga.x2rock.model.Group
import com.rahga.x2rock.network.SonosApiService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
}
