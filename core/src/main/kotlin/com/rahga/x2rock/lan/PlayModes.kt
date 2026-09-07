package com.rahga.x2rock.lan

import com.google.gson.JsonObject
import com.rahga.x2rock.model.PlayModeState
import com.rahga.x2rock.model.RepeatModes

/**
 * Sonos keeps repeat as **two booleans**, `repeat` and `repeatOne`; the app models it as
 * one of three strings. Both flags go out on every `setPlayModes` so the result never
 * depends on what the other one happened to be.
 *
 * Kept apart from the socket so the mapping can be read and tested without one.
 */
object PlayModes {

    /** `playModes` off a `playbackStatus` body. */
    fun fromJson(playModes: JsonObject?): PlayModeState {
        if (playModes == null) return PlayModeState()
        fun flag(name: String) = playModes.get(name)?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false
        return PlayModeState(
            repeat = when {
                flag("repeatOne") -> RepeatModes.ONE
                flag("repeat") -> RepeatModes.ALL
                else -> RepeatModes.NONE
            },
            shuffle = flag("shuffle"),
            crossfade = flag("crossfade"),
        )
    }

    /** The whole `setPlayModes` body. Every flag is stated, none inherited. */
    fun toBody(mode: PlayModeState): JsonObject {
        val modes = JsonObject().apply {
            addProperty("repeat", mode.repeat == RepeatModes.ALL)
            addProperty("repeatOne", mode.repeat == RepeatModes.ONE)
            addProperty("shuffle", mode.shuffle)
            addProperty("crossfade", mode.crossfade)
        }
        return JsonObject().apply { add("playModes", modes) }
    }
}
