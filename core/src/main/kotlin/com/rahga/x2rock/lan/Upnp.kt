package com.rahga.x2rock.lan

import com.rahga.x2rock.model.Favorite
import com.rahga.x2rock.model.FavoriteResource
import com.rahga.x2rock.model.FavoriteService
import com.rahga.x2rock.model.MusicObjectId
import com.rahga.x2rock.model.QueueItem
import com.rahga.x2rock.model.TruePlay
import com.rahga.x2rock.model.QueueResponse
import com.rahga.x2rock.model.Track
import com.rahga.x2rock.model.TrackAlbum
import com.rahga.x2rock.model.TrackArtist
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * The queue, which the Control API does not have.
 *
 * `queue:1`, `playbackQueue:1` and `cloudQueue:1` all answer
 * `ERROR_UNSUPPORTED_NAMESPACE` — cloud or LAN, the Control API has no view of the queue
 * the official apps populate. UPnP does, over plain HTTP on port 1400.
 *
 * **This is the one cleartext path in the app.** Android blocks cleartext by default since
 * API 28, and the exemption is scoped in `network_security_config.xml` to `.local` names
 * only — never to raw IPs, never to the internet — which works because a player's name is
 * derivable from its id. So every call here goes to a `sonos-<MAC>.local` hostname, and
 * passing a bare address will (correctly) be refused by the platform.
 *
 * UPnP *eventing* (GENA) is deliberately not used: it needs the player to open a
 * connection back to us. This is request/response only, so the queue is the one part of
 * the UI that still has to be asked rather than pushed.
 */

/**
 * The player answered, and the answer was an error — a fault, or 403 with UPnP switched off.
 * Distinct from a transport failure, where nothing came back and the request may yet have
 * been carried out.
 */
class UpnpRefusedException(
    message: String,
    /** UPnP's own error code when the fault carried one — `800` from [Upnp.runningAlarm] is an answer. */
    val upnpCode: String? = null,
) : IOException(message)

/** `GetRunningAlarmProperties`'s fault when no alarm is going off. */
internal const val NO_ALARM_RUNNING = "800"

/** Where `ReorderTracksInQueue` inserts, to move [from] to [to]: x2rock's rule. */
internal fun insertBefore(from: Int, to: Int): Int = if (to > from) to + 1 else to

/** `HH:MM:SS` to milliseconds. */
internal fun parseClock(clock: String): Long? {
    val parts = clock.split(':').map { it.toLongOrNull() ?: return null }
    if (parts.size != 3) return null
    return ((parts[0] * 60 + parts[1]) * 60 + parts[2]) * 1_000
}

/**
 * Milliseconds to `HH:MM:SS`, rounded down to the second. In ASCII digits whatever the device's
 * locale: a TV set to Arabic would otherwise send `٠٠:٣٠:٠٠`, which the player refuses with 402.
 */
internal fun formatClock(millis: Long): String {
    val total = (millis / 1_000).coerceAtLeast(0)
    return "%02d:%02d:%02d".format(Locale.ROOT, total / 3_600, total / 60 % 60, total % 60)
}

/**
 * A soundbar's TV-remote settings — see [Upnp.remoteSettings]. [irRepeater] is the word as sent:
 * the service documents a third state, `Disabled`, beside On and Off, and flattening one nobody
 * here has seen to "off" would say something untrue about the hardware.
 */
data class RemoteSettings(val feedback: Boolean, val irRepeater: String) {
    /** On or off, where the player said one of those; null for anything else. */
    val irRepeaterOn: Boolean? get() = when {
        irRepeater.equals("On", ignoreCase = true) -> true
        irRepeater.equals("Off", ignoreCase = true) -> false
        else -> null
    }
}

/**
 * What `CheckForUpdate` offers a player. **[downloadBytes] is the check, not the version**: Dining
 * Room's SYMFONISK pair runs 86.10-80260 and is offered the household's 97.1-80312 with
 * `DownloadSize` 0 — nothing it can take — which the CLI reports up to date (2026-10-10).
 */
data class SoftwareOffer(val version: String, val downloadBytes: Long) {
    val waiting: Boolean get() = downloadBytes > 0
}

/** About My System's fields for one player — see [Upnp.deviceInfo]. */
data class DeviceInfo(
    val modelName: String,
    val displayVersion: String,
    /** The build, `97.1-80312`: what an offered version is compared with. */
    val softwareVersion: String,
    val hardwareVersion: String,
    val serial: String,
)

internal fun parseDeviceInfo(body: String): DeviceInfo? {
    val root = runCatching { Xml.parse(body) }.getOrNull() ?: return null
    fun field(tag: String) = root.getElementsByTagName(tag).item(0)?.textContent?.trim().orEmpty()
    return DeviceInfo(field("modelName"), field("displayVersion"), field("softwareVersion"), field("hardwareVersion"), field("serialNum"))
}

/**
 * What a portable says about its pack. Every field may be absent or a word nobody has seen, and is
 * passed on as said rather than flattened: a battery reporting trouble is the case worth showing.
 */
data class Battery(val level: Int? = null, val health: String? = null, val temperature: String? = null, val powerSource: String? = null) {
    /** Charging unless running off the pack itself; null where it does not say. */
    val charging: Boolean? get() = powerSource?.let { !it.equals("BATTERY", ignoreCase = true) }
}

/**
 * The `<Data name="…">` rows of a `LocalBatteryStatus` block, or null where there is none. **The
 * populated shape is x2rock's, not a capture**: no household this was built against has a portable,
 * so a Roam or Move's real reply is owed (PUNCH P-16) — and release notes say battery is untested.
 */
/**
 * The Sonos favourites `getFavorites` leaves out, out of `Browse FV:2`'s DIDL. Today's Sonos app saves
 * an album, an artist or a song as `r:type` `shortcut` with an empty `res`: the item is only in
 * `r:resMD` — a service's own SMAPI id behind an eight-digit prefix, `/` written `__UNENCODED_SLASH__`
 * (`10082064album__UNENCODED_SLASH__2178485`), and the service in the cdudn's type, `type / 256`
 * (TIDAL's 44551 is service 174). The Sonos app lists them among its favourites; the Control API
 * neither lists them nor loads them (`ERROR_INVALID_OBJECT_ID`). Captured off this household's TIDAL
 * favourites, 2026-10-16. A station saved the same day is still `instantPlay`, and still listed.
 *
 * Only shortcuts to an album, artist, song or playlist: a shortcut is also what a Sonos Radio
 * navigation entry was written as (x2rock), which is a place in the Sonos app and nothing to play.
 * The account is not named (`-0-Token`), so it is left for the caller to find; the [Favorite.id] is
 * the number after `FV:2/`, which is how `getFavorites` names the others.
 */
internal fun parseFavoriteShortcuts(didl: String): List<Favorite> {
    if (didl.isBlank()) return emptyList()
    val root = runCatching { Xml.parse(didl) }.getOrNull() ?: return emptyList()
    val items = root.getElementsByTagName("item")
    return (0 until items.length).mapNotNull { i ->
        val item = items.item(i) as Element
        // Only top-level favourites: resMD is text here, so its own item is not in this tree.
        if (item.parentNode != root) return@mapNotNull null
        fun field(tag: String) = item.getElementsByTagName(tag).item(0)?.textContent?.trim()?.takeIf { it.isNotEmpty() }
        if (field("r:type") != "shortcut") return@mapNotNull null
        val meta = field("r:resMD")?.let { runCatching { Xml.parse(it) }.getOrNull() } ?: return@mapNotNull null
        val target = meta.getElementsByTagName("item").item(0) as? Element ?: return@mapNotNull null
        val type = shortcutType(target.getElementsByTagName("upnp:class").item(0)?.textContent.orEmpty()) ?: return@mapNotNull null
        val cdudn = target.getElementsByTagName("desc").item(0)?.textContent.orEmpty()
        val serviceId = SHORTCUT_SERVICE.find(cdudn)?.groupValues?.get(1)?.toIntOrNull()?.let { (it / 256).toString() }
            ?: return@mapNotNull null
        val objectId = target.getAttribute("id").replace(SHORTCUT_PREFIX, "").replace("__UNENCODED_SLASH__", "/")
        if (objectId.isEmpty()) return@mapNotNull null
        val name = field("dc:title") ?: return@mapNotNull null
        val serviceName = field("r:description")
        Favorite(
            id = item.getAttribute("id").removePrefix("FV:2/"),
            name = name,
            description = serviceName,
            imageUrl = field("upnp:albumArtURI"),
            service = FavoriteService(serviceId, serviceName),
            resource = FavoriteResource(type, MusicObjectId(objectId, serviceId)),
            shortcut = true,
        )
    }
}

private fun shortcutType(upnpClass: String): String? = when {
    upnpClass.startsWith("object.container.album") -> "ALBUM"
    upnpClass.startsWith("object.container.person") -> "ARTIST"
    upnpClass.startsWith("object.container.playlistContainer") -> "PLAYLIST"
    upnpClass.startsWith("object.item.audioItem.musicTrack") -> "TRACK"
    else -> null
}

private val SHORTCUT_PREFIX = Regex("^[0-9a-f]{8}")
private val SHORTCUT_SERVICE = Regex("^SA_RINCON(\\d+)_")

internal fun parseBattery(body: String): Battery? {
    val root = runCatching { Xml.parse(body) }.getOrNull() ?: return null
    val block = root.getElementsByTagName("LocalBatteryStatus").item(0) as? Element ?: return null
    val rows = block.getElementsByTagName("Data")
    val data = (0 until rows.length).map { rows.item(it) as Element }
        .associate { it.getAttribute("name") to it.textContent.trim() }
        .filterValues { it.isNotEmpty() }
    return Battery(data["Level"]?.toIntOrNull(), data["Health"], data["Temperature"], data["PowerSource"])
}

/** `GetMediaInfo`'s answer — see [Upnp.mediaInfo]. */
data class MediaInfo(val currentUri: String, val currentUriMetaData: String = "") {
    val playingFromQueue: Boolean get() = currentUri.startsWith("x-rincon-queue:")
}

/** `ListAvailableServices`'s reply — see [Upnp.listAvailableServices]. */
data class ServiceListAnswer(val descriptors: String, val types: String = "")

class Upnp(
    client: OkHttpClient,
    /** Overridden only by tests, which answer SOAP from a fake on an ephemeral port. */
    private val port: Int = PORT,
) {

    /**
     * The shared client with a read timeout put back.
     *
     * It is built for the WebSocket, where `readTimeout(0)` is right because a subscription
     * is meant to sit idle. Request/response is the opposite case: a player that goes quiet
     * mid-answer would hang the caller forever. That is not hypothetical here — switching a
     * soundbar's group to its HDMI input stalls AVTransport for about twelve seconds, and
     * the queue calls share the same fate whenever a player is busy. Derived rather than
     * separate, so the connection pool and dispatcher stay shared.
     */
    private val client = client.newBuilder()
        .readTimeout(READ_TIMEOUT, TimeUnit.SECONDS)
        .build()

    suspend fun browseQueue(hostname: String, start: Int = 0, count: Int = MAX_ITEMS): QueueResponse =
        withContext(Dispatchers.IO) {
            val envelope = browse(hostname, start, count)
            val total = envelope.text("TotalMatches")?.toIntOrNull() ?: 0
            // The DIDL document arrives as escaped text inside <Result>, so one layer of
            // unescaping has already happened and what is left is XML to parse again.
            val didl = envelope.text("Result").orEmpty()
            QueueResponse(items = parseDidl(didl, hostname, start), totalItems = total, updateId = envelope.text("UpdateID"))
        }

    /**
     * `Q:0/<n>` is one-based, matching the track numbers the UI shows. [updateId] is the
     * queue's version the caller's list was read at — see [moveInQueue].
     */
    suspend fun removeFromQueue(hostname: String, trackNumber: Int, updateId: String): Unit = withContext(Dispatchers.IO) {
        transport(hostname, "RemoveTrackFromQueue", "ObjectID" to "Q:0/$trackNumber", "UpdateID" to updateId)
    }

    /**
     * Move track [from] to position [to], both 1-based. `ReorderTracksInQueue` names where to
     * insert, which is one past [to] when moving down — the track leaves its old place first.
     *
     * [updateId] is the queue's `UpdateID` as the caller's list was read, so an edit made
     * against a queue someone else has since changed is refused (1028) rather than moving the
     * wrong track. This used to fetch a fresh id here first, which cost a Browse per edit and
     * meant the refusal could never happen: a fresh id always matches.
     */
    suspend fun moveInQueue(hostname: String, from: Int, to: Int, updateId: String): Unit = withContext(Dispatchers.IO) {
        transport(
            hostname, "ReorderTracksInQueue",
            "StartingIndex" to from.toString(),
            "NumberOfTracks" to "1",
            "InsertBefore" to insertBefore(from, to).toString(),
            "UpdateID" to updateId,
        )
    }

    /**
     * Add [uri] to the queue, with the DIDL [metadata] that says whose it is, leaving what plays
     * alone. A container is expanded by the player into its tracks. [position] is the 1-based slot
     * its first track takes; 0 appends.
     *
     * **"Next" is a position, not a flag**: `EnqueueAsNext` on its own still appends (x2rock,
     * verified), so a caller wanting "play next" names the slot after the current track.
     */
    suspend fun addToQueue(hostname: String, uri: String, metadata: String, position: Int = 0): Unit = withContext(Dispatchers.IO) {
        transport(
            hostname, "AddURIToQueue",
            "EnqueuedURI" to uri,
            "EnqueuedURIMetaData" to metadata,
            "DesiredFirstTrackNumberEnqueued" to position.toString(),
            "EnqueueAsNext" to if (position > 0) "1" else "0",
        )
    }

    /**
     * The queue slot playing now, 1-based, from `GetPositionInfo`'s `Track`. Means something only
     * while the queue is the source: on a stream it reads 1 whatever the queue holds.
     */
    suspend fun currentTrackNumber(hostname: String): Int = withContext(Dispatchers.IO) {
        parse(transport(hostname, "GetPositionInfo"))
            .text("Track")?.trim()?.toIntOrNull() ?: 0
    }

    /** Empty the queue. Asks for no `UpdateID`: there is no wrong track to remove. */
    suspend fun clearQueue(hostname: String): Unit = withContext(Dispatchers.IO) {
        transport(hostname, "RemoveAllTracksFromQueue")
    }

    /**
     * Save the queue as a new Sonos playlist called [title], answering its UPnP id (`SQ:6`).
     * An empty `ObjectID` is what makes it new rather than overwriting one.
     */
    suspend fun saveQueue(hostname: String, title: String): String = withContext(Dispatchers.IO) {
        val envelope = parse(transport(hostname, "SaveQueue", "Title" to title, "ObjectID" to ""))
        envelope.text("AssignedObjectID").orEmpty()
    }

    /** Delete a saved playlist by its UPnP id (`SQ:6`). For the live suite, which tidies up after itself. */
    internal suspend fun destroyObject(hostname: String, objectId: String): Unit = withContext(Dispatchers.IO) {
        soap(hostname, Service.CONTENT_DIRECTORY, "DestroyObject", listOf("ObjectID" to objectId))
    }

    suspend fun skipToQueueItem(hostname: String, trackNumber: Int): Unit = withContext(Dispatchers.IO) {
        transport(hostname, "Seek", "Unit" to "TRACK_NR", "Target" to trackNumber.toString())
    }

    /**
     * Switch a soundbar's group to its HDMI input.
     *
     * The URI names the *soundbar*, but the call goes to the group's **coordinator**, as
     * every AVTransport call does. The two differ whenever a soundbar has joined someone
     * else's group, and that case does not answer: taking the TV hands coordination to the
     * soundbar, and the old coordinator stops coordinating before it replies. Measured on
     * the sibling project, both players go silent for about twelve seconds and the switch
     * lands at roughly fourteen.
     *
     * Addressing the soundbar directly answers at once but means something else entirely —
     * the soundbar leaves the group and takes the TV alone, rather than bringing the room
     * with it. So the request stands as it is, and the caller decides what a lost answer
     * means. [SonosHousehold.useTvInput] does: it waits for the pushed `htInputFormat`
     * instead of the reply, which the sibling project could not do.
     */
    suspend fun useTvInput(hostname: String, soundbarId: String) =
        setTransportUri(hostname, tvStreamUri(soundbarId))

    /**
     * Make the coordinator's own queue the group's source again.
     *
     * After a station, a stream or the TV input, the queue is still there but no longer what
     * the transport plays, and `Seek TRACK_NR` answers **701** — verified 2026-10-01 on a One SL
     * playing Radio Paradise with seventeen tracks queued. The URI names the coordinator.
     */
    suspend fun useQueue(hostname: String, coordinatorId: String) =
        setTransportUri(hostname, "x-rincon-queue:$coordinatorId#0")

    /**
     * What the transport is playing from. `CurrentURI` says which source — `x-rincon-queue:`
     * when it is the queue, `x-sonosapi-radio:` for a service's radio, `x-sonos-htastream:`
     * on the TV — and the metadata is what putting it back needs.
     */
    suspend fun mediaInfo(hostname: String): MediaInfo = withContext(Dispatchers.IO) {
        val envelope = parse(transport(hostname, "GetMediaInfo"))
        MediaInfo(
            currentUri = envelope.text("CurrentURI").orEmpty(),
            currentUriMetaData = envelope.text("CurrentURIMetaData").orEmpty(),
        )
    }

    /**
     * The household this player belongs to, in the **long** form the Control API accepts
     * (`Sonos_xxx.yyy`), from `/status/zp`'s `HouseholdControlID`. Cleartext on 1400 and
     * answered by any player, so a player found by any means can be connected to — and it
     * works where the old way did not: asking over the socket with a deliberately malformed
     * frame, which a One SL on p20.96.1 simply ignored. Not SOAP, so UPnP being off does not
     * stop it.
     */
    suspend fun householdId(hostname: String): String? =
        statusZp(hostname)?.let { Regex("<HouseholdControlID>(Sonos_[^<.]+\\.[^<]+)</HouseholdControlID>").find(it)?.groupValues?.get(1) }

    /** The room this player is in, as `/status/zp`'s `ZoneName` says — to put a name to a household. */
    suspend fun zoneName(hostname: String): String? =
        statusZp(hostname)?.let { Regex("<ZoneName>([^<]+)</ZoneName>").find(it)?.groupValues?.get(1) }

    private suspend fun statusZp(hostname: String): String? = page(hostname, "/status/zp")

    /** One of the player's own cleartext pages, such as `/status/zp`; null for anything but a 200. */
    private suspend fun page(hostname: String, path: String): String? = withContext(Dispatchers.IO) {
        require(PlayerNames.isLocalName(hostname)) { "cleartext is only permitted for .local names" }
        client.newCall(Request.Builder().url("http://$hostname:$port$path").build()).awaitReply().let { response ->
            if (!response.isSuccessful) null else response.body
        }
    }

    /**
     * What a player says about itself — the fields the Sonos apps' About My System prints — from its
     * own `/xml/device_description.xml`, one cleartext GET (Kitchen, 2026-10-10; x2rock's `system`).
     */
    suspend fun deviceInfo(hostname: String): DeviceInfo? =
        page(hostname, "/xml/device_description.xml")?.let(::parseDeviceInfo)

    /**
     * What a player is being offered, or null where it says nothing. `CheckForUpdate` with
     * `UpdateType` `Software` and **`CachedOnly` 1**, which answers from the player's own memory of
     * the last check rather than reaching Sonos; its `Version` argument is ignored (x2rock). **Asked,
     * never applied**: this app reads update state and nothing else.
     */
    suspend fun softwareOffer(hostname: String): SoftwareOffer? = withContext(Dispatchers.IO) {
        val reply = parse(soap(hostname, Service.ZONE_GROUP_TOPOLOGY, "CheckForUpdate",
            listOf("UpdateType" to "Software", "CachedOnly" to "1", "Version" to "")))
        // The offer is an XML document carried as text, as the alarm list is.
        val item = reply.text("UpdateItem")?.takeIf { it.isNotBlank() } ?: return@withContext null
        val e = runCatching { Xml.parse(item) }.getOrNull() ?: return@withContext null
        SoftwareOffer(version = e.getAttribute("Version"), downloadBytes = e.getAttribute("DownloadSize").toLongOrNull() ?: 0)
    }

    /**
     * A portable's battery, or null from a speaker on mains — which answers 200 with an empty
     * `<ZPSupportInfo>` (Kitchen, 2026-10-10; a Beam for x2rock): the absent block is the answer.
     * **Untested against a portable**: see [parseBattery].
     */
    suspend fun battery(hostname: String): Battery? = page(hostname, "/status/batterystatus")?.let(::parseBattery)

    /**
     * The group's own sleep timer: how long until it stops, or `null` with none set.
     *
     * Sonos's timer, not this app's, so every controller sees it and it outlives the app. It
     * lives on AVTransport, sent to the coordinator, and is not pushed. Captured on the office
     * One SL, 2026-10-01: none set is an *empty* `RemainingSleepTimerDuration` at generation 0;
     * armed, it reads `HH:MM:SS` and the generation counts every arming. `00:00:00` is a real
     * state — expired and about to stop, for some seconds — so it reads as zero, not as none.
     */
    suspend fun sleepTimer(hostname: String): Long? = withContext(Dispatchers.IO) {
        val envelope = parse(transport(hostname, "GetRemainingSleepTimerDuration"))
        envelope.text("RemainingSleepTimerDuration")?.takeIf { it.isNotBlank() }?.let(::parseClock)
    }

    /**
     * Arm the group's sleep timer for [millis], or cancel it with `null`. `HH:MM:SS` only —
     * bare seconds answer 402 — and an empty duration cancels. It counts from acceptance; when
     * it fires the room pauses and keeps its place.
     */
    suspend fun setSleepTimer(hostname: String, millis: Long?): Unit = withContext(Dispatchers.IO) {
        transport(hostname, "ConfigureSleepTimer", "NewSleepTimerDuration" to (millis?.let(::formatClock) ?: ""))
    }

    /**
     * The id of the alarm going off in the coordinator's group, or null when none is.
     *
     * Captured on Kitchen (2026-10-02): ringing answers `AlarmID`; nothing running answers the
     * fault **800**, which is the answer rather than a failure. A *snoozed* alarm still answers
     * its id, with the room `PAUSED_PLAYBACK` — so "ringing" is this and playing, together.
     */
    suspend fun runningAlarm(hostname: String): Int? = withContext(Dispatchers.IO) {
        val reply = try {
            transport(hostname, "GetRunningAlarmProperties")
        } catch (e: UpnpRefusedException) {
            if (e.upnpCode == NO_ALARM_RUNNING) return@withContext null
            throw e
        }
        parse(reply).text("AlarmID")?.toIntOrNull()?.takeIf { it != 0 }
    }

    /**
     * Silence the group's ringing alarm for [millis]; it rings again after (62s for a minute, on
     * Kitchen). The room pauses meanwhile. To end an alarm outright, pause it: that leaves it
     * stopped, where `DestroyAlarm` would delete the alarm and not silence it.
     */
    suspend fun snoozeAlarm(hostname: String, millis: Long): Unit = withContext(Dispatchers.IO) {
        transport(hostname, "SnoozeAlarm", "Duration" to formatClock(millis))
    }

    /** Set the transport's source. The coordinator is who is asked; see [useTvInput]. */
    suspend fun setTransportUri(hostname: String, uri: String, metadata: String = ""): Unit =
        withContext(Dispatchers.IO) {
            transport(hostname, "SetAVTransportURI", "CurrentURI" to uri, "CurrentURIMetaData" to metadata)
        }

    /**
     * One of the extended-EQ toggles — `NightMode` and `DialogLevel`, which the Sonos app
     * calls Night Sound and Speech Enhancement.
     *
     * The Control API *reads* these, in `settings:1 getPlayerSettings`, but refuses to write
     * them: `ERROR_NO_PERMISSION`. So this is the only door to them and it is UPnP, which
     * makes these the second thing here reached over UPnP, alongside the queue — and, like
     * the queue, asked for rather than pushed. Verified on a Beam: `settings:1` accepts a
     * subscription and then sends nothing at all for a `SetEQ` write, while a re-read sees
     * it at once. So the caller re-reads; there is no event coming.
     *
     * Boolean on the hardware, despite `DialogLevel` naming a level: the level is readable
     * but only 0 and 1 are writable here. Addressed to the *soundbar itself*, never a
     * coordinator, because it is a property of that one speaker. A player with no HDMI
     * socket answers UPnP 402, so callers gate on the TV-input capability first.
     */
    suspend fun setEq(hostname: String, eqType: String, on: Boolean): Unit = withContext(Dispatchers.IO) {
        rendering(hostname, "SetEQ", "EQType" to eqType, "DesiredValue" to if (on) "1" else "0")
    }

    /** One EQ type's value, as `GetEQ` answers it: `CurrentValue`. A type the player lacks is UPnP 402. */
    suspend fun eq(hostname: String, eqType: String): Int = withContext(Dispatchers.IO) {
        parse(rendering(hostname, "GetEQ", "EQType" to eqType)).text("CurrentValue")?.trim()?.toIntOrNull()
            ?: throw IOException("GetEQ $eqType answered no value")
    }

    /**
     * An EQ type's value: a Sub's or the surrounds' level, −15..15 as the Sonos app sets them, or
     * an enable as 0/1. For the toggles [setEq] says why the soundbar itself is addressed.
     */
    suspend fun setEqValue(hostname: String, eqType: String, value: Int): Unit = withContext(Dispatchers.IO) {
        rendering(hostname, "SetEQ", "EQType" to eqType, "DesiredValue" to value.toString())
    }

    /** Bass or treble, −10..10. The player answers an empty body; re-read to see it. */
    suspend fun setTone(hostname: String, which: Tone, level: Int): Unit = withContext(Dispatchers.IO) {
        require(level in TONE_RANGE) { "$level is outside the ${TONE_RANGE.first}..${TONE_RANGE.last} the player accepts" }
        rendering(hostname, which.action, which.field to level.toString())
    }

    /**
     * Loudness alone takes a `Channel`, which bass and treble do not; without it the player
     * answers 402, which reads like a bad value rather than a missing field (x2rock).
     */
    suspend fun setLoudness(hostname: String, on: Boolean): Unit = withContext(Dispatchers.IO) {
        rendering(hostname, "SetLoudness", "Channel" to "Master", "DesiredLoudness" to if (on) "1" else "0")
    }

    /**
     * Whether a speaker's status light is lit. Per speaker, like tone: a room of bonded speakers has
     * a light on each, and this answers for the one asked. Captured off Kitchen, 2026-10-10.
     */
    suspend fun statusLight(hostname: String): Boolean = withContext(Dispatchers.IO) {
        parse(soap(hostname, Service.DEVICE_PROPERTIES, "GetLEDState", emptyList())).text("CurrentLEDState").equals("On", ignoreCase = true)
    }

    /**
     * Light it or not. **The player validates nothing here**: anything but exactly `Off` lights it
     * (x2rock: `Maybe` was accepted, and lit the light), so only these two words are ever sent.
     */
    suspend fun setStatusLight(hostname: String, on: Boolean): Unit = withContext(Dispatchers.IO) {
        soap(hostname, Service.DEVICE_PROPERTIES, "SetLEDState", listOf("DesiredLEDState" to if (on) "On" else "Off"))
    }

    /**
     * Whether a speaker's touch controls are locked — `On` means they do nothing, the Sonos app's
     * "Button Control" switch the other way round. Captured off Kitchen, 2026-10-10.
     */
    suspend fun buttonsLocked(hostname: String): Boolean = withContext(Dispatchers.IO) {
        parse(soap(hostname, Service.DEVICE_PROPERTIES, "GetButtonLockState", emptyList()))
            .text("CurrentButtonLockState").equals("On", ignoreCase = true)
    }

    /** Lock or unlock them; unvalidated by the player as the light is, and sent the same two words. */
    suspend fun setButtonsLocked(hostname: String, locked: Boolean): Unit = withContext(Dispatchers.IO) {
        soap(hostname, Service.DEVICE_PROPERTIES, "SetButtonLockState", listOf("DesiredButtonLockState" to if (locked) "On" else "Off"))
    }

    /**
     * A soundbar's TV-remote settings, captured off Bedroom's Beam (2026-10-10): whether it flashes
     * its light to acknowledge the TV remote, and whether it repeats the remote's IR on to the TV
     * behind it. Soundbars only — anything else answers a fault, so callers ask the soundbar.
     */
    suspend fun remoteSettings(hostname: String): RemoteSettings = withContext(Dispatchers.IO) {
        suspend fun one(action: String, tag: String) = parse(soap(hostname, Service.HT_CONTROL, action, emptyList())).text(tag).orEmpty().trim()
        RemoteSettings(
            feedback = one("GetLEDFeedbackState", "LEDFeedbackState").equals("On", ignoreCase = true),
            irRepeater = one("GetIRRepeaterState", "CurrentIRRepeaterState"),
        )
    }

    /**
     * The acknowledging flash. **The argument is `LEDFeedbackState`, without the `Desired` every
     * other setter here has**: Sonos's own inconsistency, and `DesiredLEDFeedbackState` is a 402
     * (x2rock). Unlike the status light, this one refuses anything but On and Off.
     */
    suspend fun setRemoteFeedback(hostname: String, on: Boolean): Unit = withContext(Dispatchers.IO) {
        soap(hostname, Service.HT_CONTROL, "SetLEDFeedbackState", listOf("LEDFeedbackState" to if (on) "On" else "Off"))
    }

    suspend fun setIrRepeater(hostname: String, on: Boolean): Unit = withContext(Dispatchers.IO) {
        soap(hostname, Service.HT_CONTROL, "SetIRRepeaterState", listOf("DesiredIRRepeaterState" to if (on) "On" else "Off"))
    }

    /** TruePlay's state, captured off Kitchen's One SL: enabled and available as `1`/`0`. */
    suspend fun trueplay(hostname: String): TruePlay = withContext(Dispatchers.IO) {
        val envelope = parse(rendering(hostname, "GetRoomCalibrationStatus"))
        TruePlay(
            enabled = envelope.text("RoomCalibrationEnabled") == "1",
            available = envelope.text("RoomCalibrationAvailable") == "1",
        )
    }

    /** Turn TruePlay's correction off or back on. Measuring a room is the Sonos app's. */
    suspend fun setTrueplay(hostname: String, on: Boolean): Unit = withContext(Dispatchers.IO) {
        rendering(hostname, "SetRoomCalibrationStatus", "RoomCalibrationEnabled" to if (on) "1" else "0")
    }

    enum class Tone(val action: String, val field: String) {
        BASS("SetBass", "DesiredBass"),
        TREBLE("SetTreble", "DesiredTreble"),
    }

    /**
     * The music services this player knows about — where ratings start. Unlike the queue,
     * nothing here caches the answer: it's a single cheap LAN call, so it's cheaper to ask
     * fresh than to track whether `AvailableServiceListVersion` moved. What *is* worth
     * caching (a service's presentation map, an internet round trip) lives in
     * [com.rahga.x2rock.smapi.RatingsCatalogue] instead.
     */
    suspend fun listAvailableServices(hostname: String): ServiceListAnswer = withContext(Dispatchers.IO) {
        val envelope = parse(soap(hostname, Service.MUSIC_SERVICES, "ListAvailableServices", emptyList()))
        ServiceListAnswer(
            descriptors = envelope.text("AvailableServiceDescriptorList").orEmpty(),
            // `serviceId * 256 + type` numbers, the only place a service's type for a cdudn is given.
            types = envelope.text("AvailableServiceTypeList").orEmpty(),
        )
    }

    /** `ContentDirectory Browse` of the queue, `Q:0`, from [start] for up to [count] items. */
    private suspend fun browse(hostname: String, start: Int, count: Int): Element = parse(
        soap(
            hostname, Service.CONTENT_DIRECTORY, "Browse",
            listOf(
                "ObjectID" to "Q:0",
                "BrowseFlag" to "BrowseDirectChildren",
                "Filter" to "*",
                "StartingIndex" to start.toString(),
                "RequestedCount" to count.toString(),
                "SortCriteria" to "",
            ),
        ),
    )

    // ---------------------------------------------------------------- alarms
    //
    // `AlarmClock:1` is household-wide: any player answers for every alarm. Nothing in the
    // Control API touches alarms.

    /** Every alarm in the household. See [parseAlarms] for why the reply is parsed twice. */
    suspend fun listAlarms(hostname: String): List<Alarm> = withContext(Dispatchers.IO) {
        parseAlarms(parse(soap(hostname, Service.ALARM_CLOCK, "ListAlarms", emptyList())).text("CurrentAlarmList").orEmpty())
    }

    /** Write [alarm] back whole — the player refuses a partial record (402); see [Alarm]. */
    suspend fun updateAlarm(hostname: String, alarm: Alarm): Unit = withContext(Dispatchers.IO) {
        soap(hostname, Service.ALARM_CLOCK, "UpdateAlarm", listOf("ID" to alarm.id.toString()) + alarm.writeArgs())
    }

    /**
     * Delete alarm [id]. An empty reply; an id the household no longer has is refused with 800
     * (both captured off Kitchen on an alarm made disarmed for it, 2026-10-11).
     */
    suspend fun destroyAlarm(hostname: String, id: Int): Unit = withContext(Dispatchers.IO) {
        soap(hostname, Service.ALARM_CLOCK, "DestroyAlarm", listOf("ID" to id.toString()))
    }

    /**
     * Create [alarm], its id ignored, answering the id the household assigns. Refused with 801
     * when the room already has an alarm at that start time, whatever its recurrence (Kitchen,
     * 2026-10-10).
     */
    suspend fun createAlarm(hostname: String, alarm: Alarm): Int = withContext(Dispatchers.IO) {
        val reply = parse(soap(hostname, Service.ALARM_CLOCK, "CreateAlarm", alarm.writeArgs()))
        reply.text("AssignedID")?.trim()?.toIntOrNull() ?: throw IOException("CreateAlarm answered without an id")
    }

    /** The household's clock, which alarm times are on, and whether it knows its timezone. */
    suspend fun householdClock(hostname: String): HouseholdClock = withContext(Dispatchers.IO) {
        val now = parse(soap(hostname, Service.ALARM_CLOCK, "GetTimeNow", emptyList())).text("CurrentLocalTime").orEmpty()
        val zone = parse(soap(hostname, Service.ALARM_CLOCK, "GetTimeZone", emptyList())).text("Index")?.trim()?.toIntOrNull() ?: -1
        HouseholdClock(localTime = now, timeZoneSet = zone >= 0)
    }

    /**
     * The household's Sonos favourites as alarm programs: `Browse FV:2`, each item's `res` and
     * `r:resMD`, which is exactly what `CreateAlarm` takes. The Control API's favourites carry ids
     * only. An item with no URI cannot be a program and is left out.
     */
    suspend fun favoritePrograms(hostname: String): List<AlarmProgram> = withContext(Dispatchers.IO) {
        val didl = favoritesDidl(hostname)
        if (didl.isBlank()) return@withContext emptyList()
        val items = parse(didl).getElementsByTagName("item")
        (0 until items.length).mapNotNull { i ->
            val item = items.item(i) as Element
            val uri = item.child("res") ?: return@mapNotNull null
            AlarmProgram(title = item.child("dc:title") ?: uri, uri = uri, metaData = item.child("r:resMD").orEmpty())
        }
    }

    /** The favourites the Control API does not list; see [parseFavoriteShortcuts]. */
    suspend fun favoriteShortcuts(hostname: String): List<Favorite> = withContext(Dispatchers.IO) {
        parseFavoriteShortcuts(favoritesDidl(hostname))
    }

    /** `Browse FV:2`: every Sonos favourite, as DIDL. */
    private suspend fun favoritesDidl(hostname: String): String {
        val envelope = parse(
            soap(
                hostname, Service.CONTENT_DIRECTORY, "Browse",
                listOf(
                    "ObjectID" to "FV:2",
                    "BrowseFlag" to "BrowseDirectChildren",
                    "Filter" to "*",
                    "StartingIndex" to "0",
                    "RequestedCount" to "100",
                    "SortCriteria" to "",
                ),
            ),
        )
        return envelope.text("Result").orEmpty()
    }

    // ---------------------------------------------------------------- SOAP

    /** An AVTransport action, on the coordinator; every one takes `InstanceID` 0 first. */
    private suspend fun transport(hostname: String, action: String, vararg args: Pair<String, String>): String =
        soap(hostname, Service.AV_TRANSPORT, action, listOf("InstanceID" to "0") + args)

    /** A RenderingControl action, on one speaker; `InstanceID` 0 first, as above. */
    private suspend fun rendering(hostname: String, action: String, vararg args: Pair<String, String>): String =
        soap(hostname, Service.RENDERING_CONTROL, action, listOf("InstanceID" to "0") + args)

    private enum class Service(val path: String, val urn: String) {
        AV_TRANSPORT("/MediaRenderer/AVTransport/Control", "urn:schemas-upnp-org:service:AVTransport:1"),
        CONTENT_DIRECTORY("/MediaServer/ContentDirectory/Control", "urn:schemas-upnp-org:service:ContentDirectory:1"),
        RENDERING_CONTROL("/MediaRenderer/RenderingControl/Control", "urn:schemas-upnp-org:service:RenderingControl:1"),
        MUSIC_SERVICES("/MusicServices/Control", "urn:schemas-upnp-org:service:MusicServices:1"),
        ALARM_CLOCK("/AlarmClock/Control", "urn:schemas-upnp-org:service:AlarmClock:1"),
        DEVICE_PROPERTIES("/DeviceProperties/Control", "urn:schemas-upnp-org:service:DeviceProperties:1"),
        HT_CONTROL("/HTControl/Control", "urn:schemas-upnp-org:service:HTControl:1"),
        ZONE_GROUP_TOPOLOGY("/ZoneGroupTopology/Control", "urn:schemas-upnp-org:service:ZoneGroupTopology:1"),
    }

    /**
     * Whether the household's UPnP switch is off, as `effectiveSettings:1` last said. Every
     * SOAP call is a 403 then, so each is refused here, at once and with the same words,
     * rather than after a round trip to be told so — and no caller need check first.
     */
    @Volatile var switchedOff = false

    private suspend fun soap(hostname: String, service: Service, action: String, args: List<Pair<String, String>>): String {
        require(PlayerNames.isLocalName(hostname)) {
            "UPnP must be addressed by a .local name: cleartext is only permitted for those"
        }
        if (switchedOff) throw UpnpRefusedException(switchedOffMessage(hostname))
        val params = args.joinToString("") { (name, value) -> "<$name>${Xml.escape(value)}</$name>" }
        val envelope = """<?xml version="1.0"?>""" +
            """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"""" +
            """ s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body>""" +
            """<u:$action xmlns:u="${service.urn}">$params</u:$action>""" +
            """</s:Body></s:Envelope>"""

        val request = Request.Builder()
            .url("http://$hostname:$port${service.path}")
            .header("SOAPAction", "\"${service.urn}#$action\"")
            .post(envelope.toRequestBody("text/xml; charset=utf-8".toMediaType()))
            .build()

        client.newCall(request).awaitReply().let { response ->
            val body = response.body
            if (response.isSuccessful) return body
            if (response.code == 403) throw UpnpRefusedException(switchedOffMessage(hostname))
            val code = errorCode(body)
            throw UpnpRefusedException("$action failed: HTTP ${response.code} ${describe(code)}", code)
        }
    }

    private fun errorCode(body: String): String? = runCatching { parse(body).text("errorCode") }.getOrNull()

    private fun switchedOffMessage(hostname: String) =
        "$hostname refused UPnP (403). Turn UPnP on in the Sonos app: Account > Privacy and Security > Connection Security"

    /** UPnP's own error code, when the fault body carries one. */
    private fun describe(code: String?): String =
        code?.let { "(UPnP $it${UPNP_ERRORS[it]?.let { name -> ": $name" } ?: ""})" } ?: ""

    // ---------------------------------------------------------------- XML

    private fun parse(xml: String): Element = Xml.parse(xml)

    /** First element with this tag name, anywhere; the envelopes here are small and flat. */
    private fun Element.text(tag: String): String? =
        getElementsByTagName(tag).takeIf { it.length > 0 }?.item(0)?.textContent

    private fun parseDidl(didl: String, hostname: String, start: Int): List<QueueItem> {
        if (didl.isBlank()) return emptyList()
        val root = runCatching { parse(didl) }.getOrNull() ?: return emptyList()
        val items = root.getElementsByTagName("item")
        return (0 until items.length).map { i ->
            val item = items.item(i) as Element
            QueueItem(
                // Sonos gives each item an id of the form Q:0/<n>; fall back to position.
                id = item.getAttribute("id").ifBlank { "Q:0/${start + i + 1}" },
                track = Track(
                    name = item.child("dc:title"),
                    artist = item.child("dc:creator")?.let { TrackArtist(it) },
                    album = item.child("upnp:album")?.let { TrackAlbum(it) },
                    imageUrl = item.child("upnp:albumArtURI")?.let { absolute(it, hostname) },
                    durationMillis = 0,
                ),
            )
        }
    }

    private fun Element.child(tag: String): String? =
        (0 until childNodes.length)
            .map { childNodes.item(it) }
            .firstOrNull { it.nodeType == Node.ELEMENT_NODE && it.nodeName == tag }
            ?.textContent
            ?.takeIf { it.isNotBlank() }

    /**
     * Art URLs come back as paths on the player that answered, so they are resolved
     * against its `.local` name rather than its address — for the same reason every other
     * call here is.
     *
     * **This makes them cleartext `.local` URLs, which most HTTP clients cannot fetch.**
     * Whatever loads these images (Coil, by default, builds its own `OkHttpClient`) has to
     * be given a client from [LanHttp], or it will have neither the address book to
     * resolve the name nor the platform's blessing to fetch it in the clear.
     */
    private fun absolute(uri: String, hostname: String): String =
        if (uri.startsWith("http")) uri else "http://$hostname:$port$uri"

    companion object {
        /**
         * A soundbar's own HDMI stream, which is a URI rather than a mode: `spdif` is what
         * Sonos calls the input on every model, optical and ARC alike.
         */
        private fun tvStreamUri(soundbarId: String) = "x-sonos-htastream:$soundbarId:spdif"

        private const val READ_TIMEOUT = 10L

        const val PORT = 1400

        /** Queues run to tens of thousands of tracks; listing stops here and says so. */
        const val MAX_ITEMS = 1000
        /** Bass and treble, as the player accepts them. */
        val TONE_RANGE = -10..10

        /** A Sub's or the surrounds' level, as the Sonos app sets them. */
        val SATELLITE_RANGE = -15..15

        private val UPNP_ERRORS = mapOf(
            "701" to "no media loaded, or not available in this state",
            "711" to "no such track in the queue",
            "402" to "invalid arguments",
            "1028" to "the queue changed while this was in flight; try again",
            "801" to "that room already has an alarm at that time",
        )
    }
}
