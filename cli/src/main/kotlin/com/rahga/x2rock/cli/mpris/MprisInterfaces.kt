@file:Suppress("FunctionName")

package com.rahga.x2rock.cli.mpris

import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.messages.DBusSignal

// Method names are the wire names: MPRIS is PascalCase and dbus-java maps by Java method name.

@DBusInterfaceName(MPRIS_ROOT_IFACE)
interface MediaPlayer2 : DBusInterface {
    fun Raise()
    fun Quit()
}

@DBusInterfaceName(MPRIS_PLAYER_IFACE)
interface MediaPlayer2Player : DBusInterface {
    fun Next()
    fun Previous()
    fun Pause()
    fun PlayPause()
    fun Stop()
    fun Play()
    fun Seek(offset: Long)
    fun SetPosition(trackId: DBusPath, position: Long)
    fun OpenUri(uri: String)

    class Seeked(path: String, position: Long) : DBusSignal(path, position)
}
