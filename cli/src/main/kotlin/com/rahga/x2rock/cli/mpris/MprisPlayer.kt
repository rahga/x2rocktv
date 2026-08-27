@file:Suppress("FunctionName")

package com.rahga.x2rock.cli.mpris

import kotlinx.coroutines.runBlocking
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.types.Variant

/**
 * The MPRIS object on the session bus. dbus-java invokes the interface methods on its own
 * threads; each runs the Sonos call to completion, then asks the daemon to re-poll so the bus
 * sees the result within one round trip instead of at the next scheduled tick.
 */
class MprisPlayer(
    private val identity: String,
    private val controls: PlayerControls,
    private val onChanged: () -> Unit
) : MediaPlayer2, MediaPlayer2Player, Properties {

    private var connection: DBusConnection? = null
    @Volatile private var snapshot: PlayerSnapshot? = null
    private val root = rootProperties(identity)

    fun connect(busSuffix: String) {
        val conn = DBusConnectionBuilder.forSessionBus().build()
        conn.requestBusName("org.mpris.MediaPlayer2.$busSuffix")
        conn.exportObject(MPRIS_PATH, this)
        connection = conn
    }

    fun disconnect() {
        connection?.disconnect()
        connection = null
    }

    fun update(new: PlayerSnapshot) {
        val changed = changedPlayerProperties(snapshot, new)
        snapshot = new
        if (changed.isNotEmpty()) emitChanged(changed)
    }

    private fun emitChanged(changed: Map<String, Variant<*>>) {
        connection?.sendMessage(Properties.PropertiesChanged(MPRIS_PATH, MPRIS_PLAYER_IFACE, changed, emptyList()))
    }

    private fun emitSeeked(positionMicros: Long) {
        connection?.sendMessage(MediaPlayer2Player.Seeked(MPRIS_PATH, positionMicros))
    }

    override fun getObjectPath(): String = MPRIS_PATH
    override fun isRemote(): Boolean = false

    // ---- org.mpris.MediaPlayer2

    override fun Raise() { /* CanRaise = false */ }
    override fun Quit() = controls.quit()

    // ---- org.mpris.MediaPlayer2.Player

    override fun Next() = act { controls.next() }
    override fun Previous() = act { controls.previous() }
    override fun Pause() = act { controls.pause() }
    override fun PlayPause() = act { controls.playPause() }
    override fun Stop() = act { controls.pause() }
    override fun Play() = act { controls.play() }
    override fun OpenUri(uri: String) { /* SupportedUriSchemes is empty */ }

    override fun Seek(offset: Long) {
        val s = snapshot ?: return
        val targetMillis = (mprisPositionMicros(s, System.currentTimeMillis()) + offset) / 1_000
        seekMillis(targetMillis.coerceAtLeast(0))
    }

    override fun SetPosition(trackId: DBusPath, position: Long) {
        val s = snapshot ?: return
        // Spec: silently ignore if the track changed under the client.
        if (trackId.path != mprisTrackId(s.track).path) return
        seekMillis(position / 1_000)
    }

    private fun seekMillis(millis: Long) = act {
        controls.seekTo(millis)
        emitSeeked(millis * 1_000)
    }

    // ---- org.freedesktop.DBus.Properties

    @Suppress("UNCHECKED_CAST")
    override fun <A> Get(iface: String, prop: String): A =
        (GetAll(iface)[prop] ?: throw IllegalArgumentException("No property $iface.$prop")) as A

    override fun GetAll(iface: String): Map<String, Variant<*>> = when (iface) {
        MPRIS_ROOT_IFACE -> root
        MPRIS_PLAYER_IFACE -> {
            val now = System.currentTimeMillis()
            val s = snapshot ?: PlayerSnapshot.idle(identity, now)
            playerProperties(s) + ("Position" to Variant(mprisPositionMicros(s, now)))
        }
        else -> emptyMap()
    }

    override fun <A> Set(iface: String, prop: String, value: A) {
        if (iface != MPRIS_PLAYER_IFACE) return
        val raw = (value as? Variant<*>)?.value ?: value
        when (prop) {
            "Volume" -> (raw as? Number)?.let { v -> act { controls.setVolume(sonosVolume(v.toDouble())) } }
            "Shuffle" -> (raw as? Boolean)?.let { on -> act { controls.setShuffle(on) } }
            "LoopStatus" -> (raw as? String)?.let { loop -> act { controls.setRepeat(sonosRepeat(loop)) } }
            // Rate is pinned at 1.0; everything else is read-only.
        }
    }

    /** Runs a control to completion, swallowing failures — the next poll shows the truth either way. */
    private fun act(block: suspend () -> Unit) {
        runCatching { runBlocking { block() } }
        onChanged()
    }
}
