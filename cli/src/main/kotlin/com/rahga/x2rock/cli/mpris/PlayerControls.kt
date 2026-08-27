package com.rahga.x2rock.cli.mpris

/** What an MPRIS client can ask the daemon to do. The daemon implements it against SonosRepository. */
interface PlayerControls {
    suspend fun playPause()
    suspend fun play()
    suspend fun pause()
    suspend fun next()
    suspend fun previous()
    suspend fun seekTo(positionMillis: Long)
    suspend fun setVolume(volume: Int)
    suspend fun setShuffle(shuffle: Boolean)
    suspend fun setRepeat(repeat: String)
    fun quit()
}
