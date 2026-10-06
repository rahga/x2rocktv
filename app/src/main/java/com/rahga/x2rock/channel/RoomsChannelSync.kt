package com.rahga.x2rock.channel

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.tvprovider.media.tv.Channel
import androidx.tvprovider.media.tv.PreviewProgram
import androidx.tvprovider.media.tv.TvContractCompat
import com.rahga.x2rock.lan.PlayerAddressBook
import com.rahga.x2rock.model.Group
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Publishing rooms to the TV home screen, behind an interface so callers stay testable. */
interface ChannelSync {
    fun sync(groups: List<Group>, tiles: Map<String, RoomTile>)
}

/**
 * What a room's tile shows beyond its name: the track under it, and the room's picture
 * ([com.rahga.x2rock.lan.GroupState.artUrl]). Only these, so a volume change or a seek does not rewrite every tile.
 */
data class RoomTile(val subtitle: String?, val artUrl: String?)

/**
 * One tile per room, keyed and linked by the room's coordinator — a *player* id — rather than
 * by its group id. A regroup mints new group ids, so a tile linked by one led nowhere after
 * the next regroup; the coordinator is what stays put, and the app resolves it to whichever
 * group holds it when the tile is opened.
 */
@Singleton
class RoomsChannelSync @Inject constructor(
    @ApplicationContext private val context: Context,
    private val addresses: PlayerAddressBook,
) : ChannelSync {
    @Volatile private var channelId = NO_ID

    /**
     * Whether there is a home screen to publish to. A phone or tablet has no TV provider, and
     * inserting the channel there threw "Unknown URL content://android.media.tv/channel" and
     * took the app down the moment the rooms loaded (a moto g on Android 15, 2026-10-06).
     */
    private val hasTvProvider: Boolean by lazy {
        context.packageManager.resolveContentProvider(TvContractCompat.AUTHORITY, 0) != null
    }
    private var lastGroups: List<Group> = emptyList()
    private var lastTiles: Map<String, RoomTile> = emptyMap()

    override fun sync(groups: List<Group>, tiles: Map<String, RoomTile>) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || !hasTvProvider) return
        synchronized(this) {
            if (groups == lastGroups && tiles == lastTiles) return
            val channelId = ensureChannel() ?: return
            // Remember only what actually reached the provider. Recording it up front meant a
            // failed write suppressed every identical retry from then on.
            val written = runCatching { updatePrograms(channelId, groups, tiles) }
            // Said in the log: a failure here leaves the home screen's row empty, and the launcher
            // hides an empty row without a word, so nothing else would ever say why it went.
            written.exceptionOrNull()?.let { Log.w(TAG, "home-screen tiles not written", it) }
            if (written.isSuccess) {
                lastGroups = groups
                lastTiles = tiles
            }
        }
    }

    // RestrictedApi here is a known AGP lint false positive on tv-provider's self-bounded
    // generic builders (Builder<T extends Builder<T>>): lint resolves setType/setTitle/etc.
    // to the base class's library scope even though none of them carry @RestrictTo — verified
    // by decompiling tvprovider-1.0.0. This is exactly the public Preview Channels API.
    @Suppress("RestrictedApi")
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

    @Suppress("RestrictedApi")
    private fun updatePrograms(channelId: Long, groups: List<Group>, tiles: Map<String, RoomTile>) {
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

        // Rows keyed by anything else — group ids, from before tiles were keyed by player —
        // are stale by the same test, so they go on the first sync.
        val currentIds = groups.map { it.coordinatorId }.toSet()
        existing.keys.filter { it !in currentIds }.forEach { stale ->
            context.contentResolver.delete(
                TvContractCompat.buildPreviewProgramUri(existing[stale]!!), null, null
            )
        }

        groups.forEachIndexed { index, group ->
            val tile = tiles[group.id]
            val builder = PreviewProgram.Builder()
                .setChannelId(channelId)
                .setType(TvContractCompat.PreviewPrograms.TYPE_CLIP)
                .setTitle(group.name)
                .setDescription(tile?.subtitle ?: "")
                .setInternalProviderId(group.coordinatorId)
                .setWeight(groups.size - index)
                .setIntentUri(Uri.parse("x2rock://room/${Uri.encode(group.coordinatorId)}"))

            PosterArt.forLauncher(tile?.artUrl, addresses)?.let { url ->
                builder.setPosterArtUri(Uri.parse(url))
                    .setPosterArtAspectRatio(TvContractCompat.PreviewPrograms.ASPECT_RATIO_16_9)
            }

            val existingId = existing[group.coordinatorId]
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

private const val TAG = "x2rock.channel"
