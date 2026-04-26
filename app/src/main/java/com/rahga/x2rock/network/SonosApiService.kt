package com.rahga.x2rock.network

import com.rahga.x2rock.model.GroupsResponse
import com.rahga.x2rock.model.HouseholdsResponse
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Path

interface SonosApiService {
    @GET("households")
    suspend fun getHouseholds(): Response<HouseholdsResponse>

    @GET("households/{householdId}/groups")
    suspend fun getGroups(@Path("householdId") householdId: String): Response<GroupsResponse>
}
