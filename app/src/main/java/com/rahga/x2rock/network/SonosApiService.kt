package com.rahga.x2rock.network

import com.rahga.x2rock.model.GroupVolume
import com.rahga.x2rock.model.GroupsResponse
import com.rahga.x2rock.model.HouseholdsResponse
import com.rahga.x2rock.model.PlaybackMetadata
import com.rahga.x2rock.model.PlaybackState
import com.rahga.x2rock.model.SetVolumeRequest
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

interface SonosApiService {
    @GET("households")
    suspend fun getHouseholds(): Response<HouseholdsResponse>

    @GET("households/{householdId}/groups")
    suspend fun getGroups(@Path("householdId") householdId: String): Response<GroupsResponse>

    @GET("groups/{groupId}/playback")
    suspend fun getPlaybackState(@Path("groupId") groupId: String): Response<PlaybackState>

    @GET("groups/{groupId}/playbackMetadata")
    suspend fun getPlaybackMetadata(@Path("groupId") groupId: String): Response<PlaybackMetadata>

    @POST("groups/{groupId}/playback/togglePlayPause")
    suspend fun togglePlayPause(
        @Path("groupId") groupId: String,
        @Body body: Map<String, @JvmSuppressWildcards Any>
    ): Response<ResponseBody>

    @POST("groups/{groupId}/playback/skipToNextTrack")
    suspend fun skipToNextTrack(
        @Path("groupId") groupId: String,
        @Body body: Map<String, @JvmSuppressWildcards Any>
    ): Response<ResponseBody>

    @POST("groups/{groupId}/playback/skipToPreviousTrack")
    suspend fun skipToPreviousTrack(
        @Path("groupId") groupId: String,
        @Body body: Map<String, @JvmSuppressWildcards Any>
    ): Response<ResponseBody>

    @GET("groups/{groupId}/groupVolume")
    suspend fun getGroupVolume(@Path("groupId") groupId: String): Response<GroupVolume>

    @POST("groups/{groupId}/groupVolume")
    suspend fun setGroupVolume(
        @Path("groupId") groupId: String,
        @Body request: SetVolumeRequest
    ): Response<GroupVolume>
}
