package com.rahga.x2rock.channel

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.tvprovider.media.tv.Channel
import androidx.tvprovider.media.tv.PreviewProgram
import androidx.tvprovider.media.tv.TvContractCompat
import com.rahga.x2rock.model.Group
import com.rahga.x2rock.model.Track
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomsChannelSync @Inject constructor(
    @ApplicationContext private val context: Context
) {
    @Volatile private var channelId = NO_ID
    private var lastGroups: List<Group> = emptyList()
    private var lastNowPlaying: Map<String, Track?> = emptyMap()

    fun sync(groups: List<Group>, nowPlaying: Map<String, Track?>) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        synchronized(this) {
            if (groups == lastGroups && nowPlaying == lastNowPlaying) return
            val channelId = ensureChannel() ?: return
            // Remember only what actually reached the provider. Recording it up front meant a
            // failed write suppressed every identical retry from then on.
            if (runCatching { updatePrograms(channelId, groups, nowPlaying) }.isSuccess) {
                lastGroups = groups
                lastNowPlaying = nowPlaying
            }
        }
    }

    private fun ensureChannel(): Long? {
        if (channelId != NO_ID) return channelId

        context.contentResolver.query(
            TvContractCompat.Channels.CONTENT_URI,
            arrayOf(TvContractCompat.Channels._ID),
            null, null, null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                channelId = cursor.getLong(0)
                return channelId
            }
        }

        val uri = context.contentResolver.insert(
            TvContractCompat.Channels.CONTENT_URI,
            Channel.Builder()
                .setType(TvContractCompat.Channels.TYPE_PREVIEW)
                .setDisplayName("Rooms")
                .setAppLinkIntentUri(Uri.parse("x2rock://home"))
                .build()
                .toContentValues()
        ) ?: return null

        channelId = ContentUris.parseId(uri)
        TvContractCompat.requestChannelBrowsable(context, channelId)
        return channelId
    }

    private fun updatePrograms(channelId: Long, groups: List<Group>, nowPlaying: Map<String, Track?>) {
        val existing = mutableMapOf<String, Long>()
        context.contentResolver.query(
            TvContractCompat.buildPreviewProgramsUriForChannel(channelId),
            arrayOf(TvContractCompat.PreviewPrograms._ID, TvContractCompat.PreviewPrograms.COLUMN_INTERNAL_PROVIDER_ID),
            null, null, null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val pid = cursor.getLong(0)
                val gid = cursor.getString(1) ?: continue
                existing[gid] = pid
            }
        }

        val currentIds = groups.map { it.id }.toSet()
        existing.keys.filter { it !in currentIds }.forEach { stale ->
            context.contentResolver.delete(
                TvContractCompat.buildPreviewProgramUri(existing[stale]!!), null, null
            )
        }

        groups.forEachIndexed { index, group ->
            val track = nowPlaying[group.id]
            val builder = PreviewProgram.Builder()
                .setChannelId(channelId)
                .setType(TvContractCompat.PreviewPrograms.TYPE_CLIP)
                .setTitle(group.name)
                .setDescription(track?.name ?: "")
                .setInternalProviderId(group.id)
                .setWeight(groups.size - index)
                .setIntentUri(Uri.parse("x2rock://room/${Uri.encode(group.id)}"))

            track?.imageUrl?.let { url ->
                builder.setPosterArtUri(Uri.parse(url))
                    .setPosterArtAspectRatio(TvContractCompat.PreviewPrograms.ASPECT_RATIO_16_9)
            }

            val existingId = existing[group.id]
            if (existingId != null) {
                context.contentResolver.update(
                    TvContractCompat.buildPreviewProgramUri(existingId),
                    builder.build().toContentValues(), null, null
                )
            } else {
                context.contentResolver.insert(
                    TvContractCompat.PreviewPrograms.CONTENT_URI,
                    builder.build().toContentValues()
                )
            }
        }
    }

    companion object {
        private const val NO_ID = -1L
    }
}
