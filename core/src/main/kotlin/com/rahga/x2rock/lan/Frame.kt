package com.rahga.x2rock.lan

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * The LAN Control API's wire format.
 *
 * Every exchange — command, reply and unsolicited event alike — is a two-element JSON
 * array, `[header, body]`. The namespaces and body shapes are the same ones the cloud
 * REST API serves, which is why `model/SonosModels.kt` needs no changes: it is the same
 * API over a different pipe.
 *
 * See `docs/lan-transport.md`; all of this is verified against real players.
 */
data class SonosHeader(
    val namespace: String? = null,
    val householdId: String? = null,
    val groupId: String? = null,
    val playerId: String? = null,
    val cmdId: String? = null,
    val response: String? = null,
    val success: Boolean? = null,
    /** `groups`, `playbackStatus`, `metadataStatus`, `groupVolume`, … */
    val type: String? = null,
    val errorCode: String? = null,
    val reason: String? = null,
)

/**
 * A frame off the wire.
 *
 * **`success` is the only discriminator.** Replies carry it; events never do. Do not try
 * to tell them apart by namespace or type — a subscription's initial snapshot and the
 * pushes that follow it share both.
 */
sealed interface SonosFrame {
    val header: SonosHeader
    val body: JsonElement
}

/** An answer to a command we sent, correlated by [SonosHeader.cmdId]. */
data class SonosReply(
    override val header: SonosHeader,
    override val body: JsonElement,
) : SonosFrame {
    val isSuccess: Boolean get() = header.success == true

    /** The player's own words when it refuses, or null when it didn't. */
    fun errorOrNull(): String? {
        if (isSuccess) return null
        val obj = body as? JsonObject
        val code = header.errorCode
            ?: obj?.get("errorCode")?.takeIf { it.isJsonPrimitive }?.asString
        val reason = header.reason
            ?: obj?.get("reason")?.takeIf { it.isJsonPrimitive }?.asString
        return listOfNotNull(code, reason).joinToString(": ").ifEmpty { "refused, with no reason given" }
    }
}

/** Something the player told us without being asked. */
data class SonosEvent(
    override val header: SonosHeader,
    override val body: JsonElement,
) : SonosFrame {
    val namespace: String get() = header.namespace.orEmpty()
    val type: String get() = header.type.orEmpty()
}

/** Serialising and parsing the two-element array. */
object Frames {
    private val gson = Gson()

    /** `[{…header…, "cmdId": "<id>"}, {…body…}]` */
    fun encode(header: JsonObject, body: JsonObject, cmdId: String): String {
        val h = header.deepCopy().apply { addProperty("cmdId", cmdId) }
        return JsonArray(2).apply { add(h); add(body) }.toString()
    }

    /** Null for anything that is not a two-element array — the players do send other traffic. */
    fun decode(text: String): SonosFrame? {
        val array = runCatching { JsonParser.parseString(text) }.getOrNull() as? JsonArray ?: return null
        if (array.size() != 2) return null
        val header = runCatching { gson.fromJson(array[0], SonosHeader::class.java) }.getOrNull() ?: return null
        val body = array[1]
        return if (header.success != null) SonosReply(header, body) else SonosEvent(header, body)
    }

    /** A command header aimed at a whole group. Use the group's *coordinator* socket. */
    fun onGroup(namespace: String, command: String, groupId: String) = JsonObject().apply {
        addProperty("namespace", namespace)
        addProperty("command", command)
        addProperty("groupId", groupId)
    }

    /** A command header aimed at one player. Use *that player's own* socket. */
    fun onPlayer(namespace: String, command: String, playerId: String) = JsonObject().apply {
        addProperty("namespace", namespace)
        addProperty("command", command)
        addProperty("playerId", playerId)
    }

    /** A command header aimed at the household. Any player's socket will do. */
    fun onHousehold(namespace: String, command: String, householdId: String) = JsonObject().apply {
        addProperty("namespace", namespace)
        addProperty("command", command)
        addProperty("householdId", householdId)
    }
}
