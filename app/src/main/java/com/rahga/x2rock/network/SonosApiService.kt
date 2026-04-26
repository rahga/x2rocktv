package com.rahga.x2rock.network

import com.rahga.x2rock.model.GroupVolume
import com.rahga.x2rock.model.GroupsResponse
import com.rahga.x2rock.model.HouseholdsResponse
import com.rahga.x2rock.model.PlayModeResponse
import com.rahga.x2rock.model.PlaybackMetadata
import com.rahga.x2rock.model.PlaybackState
import com.rahga.x2rock.model.QueueResponse
import com.rahga.x2rock.model.SeekRequest
import com.rahga.x2rock.model.SetMuteRequest
import com.rahga.x2rock.model.SetPlayModeRequest
import com.rahga.x2rock.model.SetVolumeRequest
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

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

    @POST("groups/{groupId}/playback/seek")
    suspend fun seek(
        @Path("groupId") groupId: String,
        @Body request: SeekRequest
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

    @POST("groups/{groupId}/groupVolume/mute")
    suspend fun setGroupMute(
        @Path("groupId") groupId: String,
        @Body request: SetMuteRequest
    ): Response<GroupVolume>

    @GET("groups/{groupId}/playMode")
    suspend fun getPlayMode(@Path("groupId") groupId: String): Response<PlayModeResponse>

    @POST("groups/{groupId}/playMode")
    suspend fun setPlayMode(
        @Path("groupId") groupId: String,
        @Body request: SetPlayModeRequest
    ): Response<PlayModeResponse>

    @GET("groups/{groupId}/queue")
    suspend fun getQueue(
        @Path("groupId") groupId: String,
        @Query("limit") limit: Int = 50
    ): Response<QueueResponse>

    @GET("players/{playerId}/playerVolume")
    suspend fun getPlayerVolume(@Path("playerId") playerId: String): Response<GroupVolume>

    @POST("players/{playerId}/playerVolume")
    suspend fun setPlayerVolume(
        @Path("playerId") playerId: String,
        @Body request: SetVolumeRequest
    ): Response<GroupVolume>

    @POST("players/{playerId}/playerVolume/mute")
    suspend fun setPlayerMute(
        @Path("playerId") playerId: String,
        @Body request: SetMuteRequest
    ): Response<GroupVolume>
}
