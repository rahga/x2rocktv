package com.rahga.x2rock.lan

import org.w3c.dom.Element

/**
 * One alarm, every field as `ListAlarms` reports it.
 *
 * All of them are carried because **`UpdateAlarm` demands all of them**: a record missing one is
 * refused with UPnP 402 and the alarm left as it was (captured off Kitchen, 2026-10-10, and x2rock
 * before). So changing anything is read, copy, write back whole.
 *
 * Alarms are household-wide — any player answers for every one — and name their room by a
 * *player* id, [roomUuid], not a group.
 */
data class Alarm(
    val id: Int,
    /** `HH:MM:SS`, on the *household's* clock: see [HouseholdClock]. */
    val startTime: String,
    val duration: String = "00:15:00",
    /** `ONCE`, `DAILY`, `WEEKDAYS`, `WEEKENDS`, or `ON_` and day digits, Sunday 0: see [recurrenceLabel]. */
    val recurrence: String = "ONCE",
    val enabled: Boolean = true,
    val roomUuid: String,
    val programUri: String = CHIME_URI,
    val programMetaData: String = "",
    val playMode: String = "NORMAL",
    val volume: Int = 20,
    val includeLinkedZones: Boolean = false,
) {
    val isChime: Boolean get() = programUri.startsWith("x-rincon-buzzer")

    /** What it plays, in words: "Chime", or the program's own title from its metadata. */
    val programTitle: String?
        get() = if (isChime) "Chime" else runCatching {
            Xml.parse(programMetaData).getElementsByTagName("dc:title").item(0)?.textContent
        }.getOrNull()?.takeIf { it.isNotBlank() }

    /** `HH:MM`, as a clock face shows it. */
    val time: String get() = startTime.split(':').take(2).joinToString(":")

    companion object {
        /** The speaker's built-in chime, which needs no stream and no queue. */
        const val CHIME_URI = "x-rincon-buzzer:0"
    }
}

/**
 * The alarms in `ListAlarms`' `CurrentAlarmList`, which is an XML document carried as *text*
 * inside the reply: the envelope is parsed, then this. An entry with no usable id could not be
 * addressed afterwards, so it is dropped rather than listed as one nothing can act on.
 */
internal fun parseAlarms(document: String): List<Alarm> {
    if (document.isBlank()) return emptyList()
    val nodes = Xml.parse(document).getElementsByTagName("Alarm")
    return (0 until nodes.length).mapNotNull { i ->
        val e = nodes.item(i) as Element
        val id = e.getAttribute("ID").toIntOrNull() ?: return@mapNotNull null
        Alarm(
            id = id,
            // Read as StartTime; written back as StartLocalTime. The same field, two names.
            startTime = e.getAttribute("StartTime"),
            duration = e.getAttribute("Duration"),
            recurrence = e.getAttribute("Recurrence"),
            enabled = e.getAttribute("Enabled") == "1",
            roomUuid = e.getAttribute("RoomUUID"),
            programUri = e.getAttribute("ProgramURI"),
            programMetaData = e.getAttribute("ProgramMetaData"),
            playMode = e.getAttribute("PlayMode"),
            volume = e.getAttribute("Volume").toIntOrNull() ?: 0,
            includeLinkedZones = e.getAttribute("IncludeLinkedZones") == "1",
        )
    }
}

/** The ten fields `CreateAlarm` and `UpdateAlarm` both take, in the order the player lists them. */
internal fun Alarm.writeArgs(): List<Pair<String, String>> = listOf(
    "StartLocalTime" to startTime,
    "Duration" to duration,
    "Recurrence" to recurrence,
    "Enabled" to if (enabled) "1" else "0",
    "RoomUUID" to roomUuid,
    "ProgramURI" to programUri,
    "ProgramMetaData" to programMetaData,
    "PlayMode" to playMode,
    "Volume" to volume.toString(),
    "IncludeLinkedZones" to if (includeLinkedZones) "1" else "0",
)

/**
 * A recurrence as a person says it. `ON_` and digits is accepted by the player though its own
 * description lists only the four words (x2rock, and `ON_135` read back from Kitchen, 2026-10-10).
 * Anything else is shown as sent rather than guessed at.
 */
fun recurrenceLabel(recurrence: String): String = when (recurrence) {
    "ONCE" -> "Once"
    "DAILY" -> "Every day"
    "WEEKDAYS" -> "Weekdays"
    "WEEKENDS" -> "Weekends"
    else -> recurrence.removePrefix("ON_").takeIf { recurrence.startsWith("ON_") && it.isNotEmpty() && it.all { c -> c in '0'..'6' } }
        ?.map { DAYS[it - '0'] }?.joinToString(", ")
        ?: recurrence
}

private val DAYS = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")

/**
 * The household's own clock, which is what an alarm's time is on — not this device's. With no
 * timezone set (`GetTimeZone` index -1) it runs UTC, and "07:00" then means 07:00 UTC (x2rock,
 * 2026-09-04). Said, never converted.
 */
data class HouseholdClock(
    /** `YYYY-MM-DD HH:MM:SS`, as `GetTimeNow` reports it. */
    val localTime: String,
    val timeZoneSet: Boolean,
) {
    /** `HH:MM`. */
    val time: String get() = localTime.substringAfter(' ').split(':').take(2).joinToString(":")
}

/** A favourite as a program for an alarm: the URI and metadata `CreateAlarm` takes, from `Browse FV:2`. */
data class AlarmProgram(val title: String, val uri: String, val metaData: String)
