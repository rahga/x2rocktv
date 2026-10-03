package com.rahga.x2rock.viewmodel

import com.rahga.x2rock.model.QueueItem
import com.rahga.x2rock.model.Track
import org.junit.Assert.assertEquals
import org.junit.Test

class QueueEntriesTest {

    private fun item(id: String, deleted: Boolean = false) =
        QueueItem(id = id, track = Track(id, null, null, null), deleted = deleted)

    @Test
    fun `numbers are one-based when nothing is deleted`() {
        val entries = queueEntries(listOf(item("a"), item("b"), item("c")))
        assertEquals(listOf(1, 2, 3), entries.map { it.trackNumber })
    }

    @Test
    fun `tombstones are hidden but keep their slot`() {
        val entries = queueEntries(
            listOf(item("a"), item("gone", deleted = true), item("c"), item("d"))
        )
        assertEquals(listOf("a", "c", "d"), entries.map { it.item.id })
        // "c" is the third item in the queue even though it renders second — this is the
        // off-by-one that made the wrong track play.
        assertEquals(listOf(1, 3, 4), entries.map { it.trackNumber })
    }

    /** A move goes past what is shown next to it, which across a tombstone is slots away. */
    @Test
    fun `neighbours are the entries shown either side, across tombstones`() {
        val entries = queueEntries(listOf(item("a"), item("b"), item("gone", deleted = true), item("d")))
        assertEquals(Pair(null, 2), neighbourSlots(entries, 1))
        assertEquals(Pair(1, 4), neighbourSlots(entries, 2))
        assertEquals(Pair(2, null), neighbourSlots(entries, 4))
        assertEquals(Pair(null, null), neighbourSlots(entries, 3))
    }

    @Test
    fun `an all-deleted queue yields nothing`() {
        assertEquals(emptyList<QueueEntry>(), queueEntries(listOf(item("a", deleted = true))))
    }

    @Test
    fun `an empty queue yields nothing`() {
        assertEquals(emptyList<QueueEntry>(), queueEntries(emptyList()))
    }
}
