package com.rahga.x2rock.cli

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.mordant.rendering.TextStyles.dim
import com.rahga.x2rock.cli.mpris.MprisPlayer
import com.rahga.x2rock.cli.mpris.PlayerControls
import com.rahga.x2rock.cli.mpris.PlayerSnapshot
import com.rahga.x2rock.model.Group
import com.rahga.x2rock.model.PlayModeState
import com.rahga.x2rock.model.isPlaying
import com.rahga.x2rock.viewmodel.nextBackoffMillis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Publishes one room to the session bus as an MPRIS player so playerctl, Waybar's mpris module,
 * and media keys drive Sonos like any local player. Polls on [interval]; an MPRIS command
 * triggers an immediate re-poll. Failures back off exactly as the TV app's pollers do.
 */
class Daemon : SonosCommand("daemon") {
    override fun help(context: Context) =
        "Run in the background, exposing the room over MPRIS (playerctl, Waybar, media keys)."

    private val interval by option("--interval", help = "Poll interval in seconds").int().default(5)
    private val busName by option("--bus-name", help = "Suffix after org.mpris.MediaPlayer2.").default("x2rocktv")

    private val refresh = Channel<Unit>(Channel.CONFLATED)
    private val stopping = AtomicBoolean(false)

    override suspend fun execute() {
        var group = room()
        val identity = "Sonos — ${group.name}"

        val controls = object : PlayerControls {
            override suspend fun playPause() { sonos.repo.togglePlayPause(group.id).getOrThrow() }
            override suspend fun play() {
                if (!sonos.repo.getPlaybackState(group.id).getOrThrow().playbackState.isPlaying()) playPause()
            }
            override suspend fun pause() {
                if (sonos.repo.getPlaybackState(group.id).getOrThrow().playbackState.isPlaying()) playPause()
            }
            override suspend fun next() { sonos.repo.skipToNextTrack(group.id).getOrThrow() }
            override suspend fun previous() { sonos.repo.skipToPreviousTrack(group.id).getOrThrow() }
            override suspend fun seekTo(positionMillis: Long) { sonos.repo.seek(group.id, positionMillis).getOrThrow() }
            override suspend fun setVolume(volume: Int) { sonos.repo.setGroupVolume(group.id, volume).getOrThrow() }
            override suspend fun setShuffle(shuffle: Boolean) = setMode { copy(shuffle = shuffle) }
            override suspend fun setRepeat(repeat: String) = setMode { copy(repeat = repeat) }
            override fun quit() { stopping.set(true); refresh.trySend(Unit) }

            private suspend fun setMode(change: PlayModeState.() -> PlayModeState) {
                val current = sonos.repo.getPlayMode(group.id).getOrThrow().playMode
                sonos.repo.setPlayMode(group.id, current.change()).getOrThrow()
            }
        }

        val player = MprisPlayer(identity, controls) { refresh.trySend(Unit) }
        player.connect(busName)
        t.println("MPRIS: org.mpris.MediaPlayer2.$busName → ${group.name}  ${dim("(Ctrl-C to stop)")}")
        Runtime.getRuntime().addShutdownHook(Thread { player.disconnect() })

        val baseWait = interval * 1_000L
        var wait = baseWait
        try {
            while (currentCoroutineContext().isActive && !stopping.get()) {
                wait = try {
                    player.update(snapshot(group))
                    baseWait
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // The group id changes whenever rooms are grouped or ungrouped; find it again by name.
                    group = runCatching { room(sonos.groups()) }.getOrDefault(group)
                    t.println(dim("poll failed: ${e.message ?: e::class.simpleName}"))
                    nextBackoffMillis(wait, baseWait, e)
                }
                withTimeoutOrNull(wait) { refresh.receive() }
            }
        } finally {
            player.disconnect()
        }
    }

    private suspend fun snapshot(group: Group): PlayerSnapshot = coroutineScope {
        val p = async { sonos.repo.getPlaybackState(group.id).getOrThrow() }
        val m = async { sonos.repo.getPlaybackMetadata(group.id).getOrNull() }
        val v = async { sonos.repo.getGroupVolume(group.id).getOrThrow() }
        val pm = async { sonos.repo.getPlayMode(group.id).getOrNull()?.playMode ?: PlayModeState() }
        val playback = p.await()
        val volume = v.await()
        val mode = pm.await()
        PlayerSnapshot(
            roomName = group.name,
            playbackState = playback.playbackState,
            track = m.await()?.currentItem?.track,
            positionMillis = playback.positionMillis,
            volume = volume.volume,
            muted = volume.muted,
            shuffle = mode.shuffle,
            repeat = mode.repeat,
            capturedAtMillis = System.currentTimeMillis()
        )
    }
}
