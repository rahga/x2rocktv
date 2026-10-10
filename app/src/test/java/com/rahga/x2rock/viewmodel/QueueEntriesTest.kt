package com.rahga.x2rock.viewmodel

import com.rahga.x2rock.model.QueueItem
import com.rahga.x2rock.model.Track
import org.junit.Assert.assertEquals
import org.junit.Test

class QueueEntriesTest {

    private fun item(id: String) = QueueItem(id = id, track = Track(id, null, null, null))

    @Test
    fun `numbers are one-based`() {
        val entries = queueEntries(listOf(item("a"), item("b"), item("c")))
        assertEquals(listOf(1, 2, 3), entries.map { it.trackNumber })
    }

    @Test
    fun `neighbours are the entries shown either side`() {
        val entries = queueEntries(listOf(item("a"), item("b"), item("c")))
        assertEquals(Pair(null, 2), neighbourSlots(entries, 1))
        assertEquals(Pair(1, 3), neighbourSlots(entries, 2))
        assertEquals(Pair(2, null), neighbourSlots(entries, 3))
        assertEquals(Pair(null, null), neighbourSlots(entries, 4))
    }

    @Test
    fun `an empty queue yields nothing`() {
        assertEquals(emptyList<QueueEntry>(), queueEntries(emptyList()))
    }
}
