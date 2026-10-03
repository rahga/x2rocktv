package com.rahga.x2rock.viewmodel

import com.rahga.x2rock.model.PlaybackStates
import org.junit.Assert.assertEquals
import org.junit.Test

/** What a room-list row says under the room's name. The stream values are SomaFM's capture. */
class RoomRowLinesTest {

    private fun stream(streamInfo: String?) = HomeViewModel.RoomInfo(
        isRadio = true, source = "ice1.somafm.com", streamInfo = streamInfo,
    )

    @Test fun `a stream's own text goes on top, its host beneath`() {
        assertEquals(
            listOf("Blancmange - Don't Tell Me", "ice1.somafm.com"),
            roomRowLines(stream("Blancmange - Don't Tell Me"), PlaybackStates.PLAYING),
        )
    }

    /** Before the first ICY title, or from a station that sends none: named once, by its host. */
    @Test fun `a stream with no text of its own is named by its host, not by the transport`() {
        assertEquals(listOf("ice1.somafm.com"), roomRowLines(stream(null), PlaybackStates.PLAYING))
    }

    @Test fun `a TV input gives its format, then its source`() {
        val tv = HomeViewModel.RoomInfo(onTvInput = true, inputFormat = "Dolby Digital 5.1", source = "TV Audio")
        assertEquals(listOf("Dolby Digital 5.1", "TV Audio"), roomRowLines(tv, PlaybackStates.PLAYING))
    }

    /** The chime's container is named `x-rincon-buzzer:0` and has no track: it would read "Playing". */
    @Test fun `a ringing alarm says so`() {
        val chime = HomeViewModel.RoomInfo(source = "x-rincon-buzzer:0", alarmRinging = true)
        assertEquals(listOf("Alarm"), roomRowLines(chime, PlaybackStates.PLAYING))
    }

    @Test fun `a room with nothing loaded says its state`() {
        assertEquals(listOf("Idle"), roomRowLines(HomeViewModel.RoomInfo(), PlaybackStates.IDLE))
    }
}
