package com.rahga.x2rock.media

import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** What the system is told about the current track, and the commands it sends back. */
interface NowPlayingPublisher {

    /** Play/pause, next, previous and seek, arriving from media keys or voice. */
    interface Controls {
        fun togglePlayPause()
        fun next()
        fun previous()
        /** Absolute position the system asked for, in milliseconds. */
        fun seekTo(positionMillis: Long)
    }

    fun attach(controls: Controls)
    fun publish(title: String?, artist: String?, album: String?, durationMillis: Long)
    fun publishState(playing: Boolean, hasTrack: Boolean, positionMillis: Long)
    fun release()
}

/**
 * The real one, over the platform `MediaSession`.
 *
 * Extracted from `PlayerViewModel`, which used to build a `MediaSession` in a field
 * initializer — unconstructible in a plain JUnit test, and the single reason none of that
 * view model's logic had any coverage.
 */
@Singleton
class MediaSessionPublisher @Inject constructor(
    @ApplicationContext context: Context,
) : NowPlayingPublisher {

    private val session = MediaSession(context, "x2rock").apply {
        // Without these the system never nominates this as the media button target:
        // `dumpsys media_session` reports flags=0 and a MEDIA_PAUSE key does nothing, which
        // also leaves voice transport with nowhere to land. Deprecated since API 26 on the
        // theory every session handles buttons; API 30 disagrees.
        @Suppress("DEPRECATION")
        setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS)
        isActive = true
    }

    override fun attach(controls: NowPlayingPublisher.Controls) {
        session.setCallback(object : MediaSession.Callback() {
            override fun onPlay() = controls.togglePlayPause()
            override fun onPause() = controls.togglePlayPause()
            override fun onSkipToNext() = controls.next()
            override fun onSkipToPrevious() = controls.previous()
            override fun onSeekTo(pos: Long) = controls.seekTo(pos)
        })
    }

    override fun publish(title: String?, artist: String?, album: String?, durationMillis: Long) {
        session.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title ?: "")
                .putString(MediaMetadata.METADATA_KEY_ARTIST, artist ?: "")
                .putString(MediaMetadata.METADATA_KEY_ALBUM, album ?: "")
                .putLong(MediaMetadata.METADATA_KEY_DURATION, durationMillis)
                .build()
        )
    }

    override fun publishState(playing: Boolean, hasTrack: Boolean, positionMillis: Long) {
        val state = when {
            !hasTrack -> PlaybackState.STATE_NONE
            playing -> PlaybackState.STATE_PLAYING
            else -> PlaybackState.STATE_PAUSED
        }
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                        PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT or
                        PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_SEEK_TO
                )
                .setState(state, positionMillis, 1.0f)
                .build()
        )
    }

    override fun release() = session.release()
}
