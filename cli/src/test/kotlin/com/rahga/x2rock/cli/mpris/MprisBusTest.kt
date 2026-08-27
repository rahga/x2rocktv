package com.rahga.x2rock.cli.mpris

import com.rahga.x2rock.model.PlaybackStates
import com.rahga.x2rock.model.RepeatModes
import com.rahga.x2rock.model.Track
import com.rahga.x2rock.model.TrackArtist
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Exports a real MprisPlayer onto the session bus and drives it with busctl, the way playerctl
 * or Waybar would. This is the only place the dbus-java plumbing — export, Properties dispatch,
 * Variant signatures — is actually exercised. Skips when there is no bus or no busctl.
 */
class MprisBusTest {

    private class FakeControls : PlayerControls {
        val calls = mutableListOf<String>()
        override suspend fun playPause() { calls += "playPause" }
        override suspend fun play() { calls += "play" }
        override suspend fun pause() { calls += "pause" }
        override suspend fun next() { calls += "next" }
        override suspend fun previous() { calls += "previous" }
        override suspend fun seekTo(positionMillis: Long) { calls += "seekTo:$positionMillis" }
        override suspend fun setVolume(volume: Int) { calls += "setVolume:$volume" }
        override suspend fun setShuffle(shuffle: Boolean) { calls += "setShuffle:$shuffle" }
        override suspend fun setRepeat(repeat: String) { calls += "setRepeat:$repeat" }
        override fun quit() { calls += "quit" }
    }

    private val suffix = "x2rocktest_${ProcessHandle.current().pid()}"
    private val busName = "org.mpris.MediaPlayer2.$suffix"
    private val controls = FakeControls()
    private var refreshes = 0
    private lateinit var player: MprisPlayer

    @Before
    fun connect() {
        assumeTrue("no session bus", !System.getenv("DBUS_SESSION_BUS_ADDRESS").isNullOrBlank())
        assumeTrue("no busctl", File("/usr/bin/busctl").exists())
        player = MprisPlayer("Sonos Test", controls) { refreshes++ }  // ASCII: busctl octal-escapes non-ASCII in its output
        player.connect(suffix)
        player.update(
            PlayerSnapshot(
                roomName = "Test", playbackState = PlaybackStates.PLAYING,
                track = Track("Song", TrackArtist("Band"), null, null, durationMillis = 200_000),
                positionMillis = 10_000, volume = 40, muted = false, shuffle = false,
                repeat = RepeatModes.NONE, capturedAtMillis = System.currentTimeMillis()
            )
        )
    }

    @After
    fun disconnect() {
        if (this::player.isInitialized) player.disconnect()
    }

    private fun busctl(vararg args: String): String {
        val p = ProcessBuilder("busctl", "--user", *args).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        assertTrue("busctl ${args.joinToString(" ")} failed:\n$out", p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0)
        return out.trim()
    }

    @Test
    fun `the object introspects with both MPRIS interfaces`() {
        val xml = busctl("introspect", "--xml-interface", busName, MPRIS_PATH)
        assertTrue(xml.contains("""interface name="org.mpris.MediaPlayer2""""))
        assertTrue(xml.contains("""interface name="org.mpris.MediaPlayer2.Player""""))
        assertTrue(xml.contains("""method name="PlayPause""""))
        assertTrue(xml.contains("""interface name="org.freedesktop.DBus.Properties""""))
    }

    @Test
    fun `properties read back with the right types`() {
        assertEquals("s \"Playing\"", busctl("get-property", busName, MPRIS_PATH, MPRIS_PLAYER_IFACE, "PlaybackStatus"))
        assertEquals("d 0.4", busctl("get-property", busName, MPRIS_PATH, MPRIS_PLAYER_IFACE, "Volume"))
        assertEquals("s \"Sonos Test\"", busctl("get-property", busName, MPRIS_PATH, MPRIS_ROOT_IFACE, "Identity"))
        assertEquals("b true", busctl("get-property", busName, MPRIS_PATH, MPRIS_PLAYER_IFACE, "CanSeek"))

        val position = busctl("get-property", busName, MPRIS_PATH, MPRIS_PLAYER_IFACE, "Position")
        val micros = position.removePrefix("x ").toLong()
        assertTrue("position $micros should be >= 10s", micros >= 10_000_000L)

        val metadata = busctl("get-property", busName, MPRIS_PATH, MPRIS_PLAYER_IFACE, "Metadata")
        assertTrue(metadata, metadata.contains("\"xesam:title\" s \"Song\""))
        assertTrue(metadata, metadata.contains("\"xesam:artist\" as 1 \"Band\""))
        assertTrue(metadata, metadata.contains("\"mpris:length\" x 200000000"))
    }

    @Test
    fun `GetAll returns the player properties as a dictionary`() {
        val all = busctl("call", busName, MPRIS_PATH, "org.freedesktop.DBus.Properties", "GetAll", "s", MPRIS_PLAYER_IFACE)
        assertTrue(all, all.contains("\"PlaybackStatus\" s \"Playing\""))
        assertTrue(all, all.contains("\"CanControl\" b true"))
    }

    @Test
    fun `method calls reach the controls and trigger a refresh`() {
        busctl("call", busName, MPRIS_PATH, MPRIS_PLAYER_IFACE, "PlayPause")
        busctl("call", busName, MPRIS_PATH, MPRIS_PLAYER_IFACE, "Next")
        busctl("call", busName, MPRIS_PATH, MPRIS_PLAYER_IFACE, "Seek", "x", "5000000")
        assertEquals(listOf("playPause", "next"), controls.calls.take(2))
        assertTrue(controls.calls[2], controls.calls[2].startsWith("seekTo:15"))
        assertEquals(3, refreshes)
    }

    @Test
    fun `setting Volume over the bus lands as a Sonos percentage`() {
        busctl("set-property", busName, MPRIS_PATH, MPRIS_PLAYER_IFACE, "Volume", "d", "0.65")
        assertEquals(listOf("setVolume:65"), controls.calls)
    }

    @Test
    fun `setting LoopStatus maps back to a Sonos repeat mode`() {
        busctl("set-property", busName, MPRIS_PATH, MPRIS_PLAYER_IFACE, "LoopStatus", "s", "Track")
        assertEquals(listOf("setRepeat:${RepeatModes.ONE}"), controls.calls)
    }
}
