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
    /**
     * @param idle nothing is loaded at all. **Not** "has no title": a soundbar playing TV
     *   audio reports no track metadata while very much playing, and calling that idle
     *   makes the system treat the session as inactive — media keys and voice transport
     *   then have nowhere to land for exactly the room most likely to be in use.
     */
    fun publishState(playing: Boolean, idle: Boolean, positionMillis: Long)

    /**
     * Whether this room is something a media session should represent at all.
     *
     * False for a soundbar on its HDMI input. The television is the source there: transport
     * means nothing, there is no track to name, and the Google TV home screen's media card
     * would only add a row saying so — it rendered the empty title as "Unknown".
     *
     * Still true while a room is merely **idle**, which is not the same case. A play key or
     * a voice "play in the living room" has to land somewhere, and idle is precisely when
     * one is worth acting on.
     */
    fun setPresenting(presenting: Boolean)

    /**
     * Stop routing controls to [controls], if they are still the ones attached.
     *
     * Deliberately *not* a release: the session belongs to the application, not to a view
     * model. A view model going away must not destroy it, or the next one would attach to a
     * dead session and media keys would stop working with nothing to show why.
     *
     * And identity-checked, because a replacement view model is constructed *before* the
     * one it replaces is cleared. An unconditional detach would let the outgoing one unhook
     * the incoming one, leaving a session that publishes state but answers no keys — which
     * is indistinguishable, from the outside, from the transport being broken.
     *
     * Detaching must also stand the session *down*. Left active while advertising its last
     * state, it keeps being nominated as the system's media-button target while answering
     * nothing — so keys do not reach it and do not fall through to another app either.
     * That is worse than having no session at all.
     */
    fun detach(controls: Controls)
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
        // Activated on attach, not here: an active session with no callback is worse than
        // none, because the system still routes keys to it.
        // Without these the system never nominates this as the media button target:
        // `dumpsys media_session` reports flags=0 and a MEDIA_PAUSE key does nothing, which
        // also leaves voice transport with nowhere to land. Deprecated since API 26 on the
        // theory every session handles buttons; API 30 disagrees.
        @Suppress("DEPRECATION")
        setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS)
    }

    @Volatile private var attached: NowPlayingPublisher.Controls? = null

    @Volatile private var presenting = true

    override fun attach(controls: NowPlayingPublisher.Controls) {
        attached = controls
        session.isActive = presenting
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

    override fun publishState(playing: Boolean, idle: Boolean, positionMillis: Long) {
        val state = when {
            idle -> PlaybackState.STATE_NONE
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

    override fun setPresenting(presenting: Boolean) {
        if (this.presenting == presenting) return
        this.presenting = presenting
        // Only ever active with a callback behind it: an active session that answers nothing
        // is worse than none, because the system still routes keys to it.
        session.isActive = presenting && attached != null
    }

    override fun detach(controls: NowPlayingPublisher.Controls) {
        if (attached !== controls) return
        attached = null
        session.setCallback(null)
        publishState(playing = false, idle = true, positionMillis = 0)
        session.isActive = false
    }
}
