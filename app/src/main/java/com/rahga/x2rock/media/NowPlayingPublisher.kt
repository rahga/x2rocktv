package com.rahga.x2rock.media

import com.rahga.x2rock.channel.PosterArt
import com.rahga.x2rock.lan.PlayerAddressBook
import com.rahga.x2rock.model.PlaybackActions
import android.content.Context
import android.graphics.Bitmap
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** What the system is told about the current track, and the commands it sends back. */
interface NowPlayingPublisher {
    // Transport only, deliberately. There is no member here for volume and there must not
    // be one: `MediaSession.setPlaybackToRemote(VolumeProvider)` would route the remote's
    // volume keys to the selected room instead of the device's own output, and audio
    // reaching the speakers over HDMI/ARC is already what those keys control. Someone must
    // be able to mute what they are hearing without this app in the way. See CLAUDE.md,
    // "The remote's volume keys are not ours to take".


    /** Play/pause, next, previous and seek, arriving from media keys or voice. */
    interface Controls {
        fun togglePlayPause()
        /** An explicit Play: never pauses. And an explicit Pause: never resumes. */
        fun play()
        fun pause()
        fun next()
        fun previous()
        /** Absolute position the system asked for, in milliseconds. */
        fun seekTo(positionMillis: Long)
    }

    fun attach(controls: Controls)
    /** [artUrl] is the room's art as the app has it; the publisher makes it one the system can fetch. */
    fun publish(title: String?, artist: String?, album: String?, durationMillis: Long, artUrl: String?)
    /**
     * @param idle nothing is loaded at all. **Not** "has no title": a soundbar playing TV
     *   audio reports no track metadata while very much playing, and calling that idle
     *   makes the system treat the session as inactive — media keys and voice transport
     *   then have nowhere to land for exactly the room most likely to be in use.
     */
    fun publishState(
        playing: Boolean,
        idle: Boolean,
        positionMillis: Long,
        /**
         * What the source permits, so the system offers only that: a live station refuses
         * skip and seek, and a launcher card or a voice "next" offering them would do
         * nothing. Seek arrives already gated on there being a duration, as the pane gates it.
         */
        actions: PlaybackActions = PlaybackActions(),
    )

    /**
     * Whether this room is something a media session should represent at all.
     *
     * False for a soundbar on its HDMI input. The television is the source there: transport
     * means nothing, there is no track to name, and the Google TV home screen's media card
     * would only add a row saying so — it rendered the empty title as "Unknown".
     *
     * Still true while a room is merely **idle**, which is not the same case. A play key or
     * a voice "play in the living room" has to land somewhere, and idle is precisely when
     * one is worth acting on. False, too, before there is any room to name (P-91).
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
    @ApplicationContext private val context: Context,
    private val addresses: PlayerAddressBook,
) : NowPlayingPublisher {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    /** The cover fetched for [coverUrl], so a track on the same album needs no second fetch. */
    private var coverUrl: String? = null
    private var cover: Bitmap? = null
    private var coverJob: Job? = null
    /** Bumped on every publish, so a cover that arrives late never lands on a newer track. */
    private var published = 0

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

    /**
     * Not until the player says there is a room to name: an attach before any room is known — still
     * connecting, or a household that never answered — would advertise a session with a blank title,
     * the launcher's "Unknown" card (P-91, 2026-10-23).
     */
    @Volatile private var presenting = false

    override fun attach(controls: NowPlayingPublisher.Controls) {
        attached = controls
        session.isActive = presenting
        session.setCallback(object : MediaSession.Callback() {
            // Each as asked: both used to toggle, so a "play" to a playing room paused it
            // (outside review, 2026-10-08).
            override fun onPlay() = controls.play()
            override fun onPause() = controls.pause()
            override fun onSkipToNext() = controls.next()
            override fun onSkipToPrevious() = controls.previous()
            override fun onSeekTo(pos: Long) = controls.seekTo(pos)
        })
    }

    override fun publish(title: String?, artist: String?, album: String?, durationMillis: Long, artUrl: String?) {
        val now = ++published
        fun metadata(bitmap: Bitmap?) =
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title ?: "")
                .putString(MediaMetadata.METADATA_KEY_ARTIST, artist ?: "")
                .putString(MediaMetadata.METADATA_KEY_ALBUM, album ?: "")
                .putLong(MediaMetadata.METADATA_KEY_DURATION, durationMillis)
                // The cover, for the home screen's media card and Assistant's, which drew a blank
                // without one (outside review, 2026-10-08). The system fetches it itself, so it is
                // made fetchable the way the room tiles' posters are: a speaker's own art by its
                // address rather than its .local name, a service's https as it is, else nothing.
                .apply {
                    PosterArt.forLauncher(artUrl, addresses)?.let {
                        putString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI, it)
                        putString(MediaMetadata.METADATA_KEY_ART_URI, it)
                    }
                    // And the picture itself: the Shield's card drew a blank icon with only the URIs,
                    // https and speaker art alike (2026-10-11) — the system opens an art URI through a
                    // ContentResolver, which takes no http. Fetched here by the app's own loader.
                    bitmap?.let {
                        putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it)
                        putBitmap(MediaMetadata.METADATA_KEY_ART, it)
                    }
                }
                .build()
        if (artUrl == null) {
            coverJob?.cancel(); coverUrl = null; cover = null
            session.setMetadata(metadata(null))
            return
        }
        if (artUrl == coverUrl && cover != null) {
            session.setMetadata(metadata(cover))
            return
        }
        session.setMetadata(metadata(null))
        coverJob?.cancel()
        coverJob = scope.launch {
            val request = ImageRequest.Builder(context).data(artUrl).size(COVER_PX).allowHardware(false).build()
            val bitmap = (context.imageLoader.execute(request) as? SuccessResult)?.image?.toBitmap()
                ?: return@launch
            coverUrl = artUrl
            cover = bitmap
            if (now == published) session.setMetadata(metadata(bitmap))
        }
    }

    override fun publishState(playing: Boolean, idle: Boolean, positionMillis: Long, actions: PlaybackActions) {
        // Play/pause stays whenever anything of the kind is possible: on a live stream
        // "pause" is a stop, and the command is the same either way.
        var advertised = 0L
        if (actions.canPlay) advertised = advertised or PlaybackState.ACTION_PLAY
        if (actions.canPause || actions.canStop) advertised = advertised or PlaybackState.ACTION_PAUSE
        if (actions.canPlay || actions.canPause || actions.canStop) {
            advertised = advertised or PlaybackState.ACTION_PLAY_PAUSE
        }
        if (actions.canSkip) advertised = advertised or PlaybackState.ACTION_SKIP_TO_NEXT
        if (actions.canGoBack) advertised = advertised or PlaybackState.ACTION_SKIP_TO_PREVIOUS
        if (actions.canSeek) advertised = advertised or PlaybackState.ACTION_SEEK_TO
        val state = when {
            idle -> PlaybackState.STATE_NONE
            playing -> PlaybackState.STATE_PLAYING
            else -> PlaybackState.STATE_PAUSED
        }
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(advertised)
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
        publishState(playing = false, idle = true, positionMillis = 0, actions = PlaybackActions())
        session.isActive = false
    }
}

/** The cover's edge for the system's card: enough for it, small enough to send across processes. */
private const val COVER_PX = 320
