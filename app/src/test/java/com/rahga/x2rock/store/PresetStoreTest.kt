package com.rahga.x2rock.store

import com.rahga.x2rock.viewmodel.FakePreferences
import org.junit.Assert.assertEquals
import org.junit.Test

class PresetStoreTest {

    private val dinner = Preset(
        id = "a", name = "Dining Room + Kitchen · Love Songs Radio",
        playerIds = listOf("RINCON_1", "RINCON_2"), volumes = mapOf("RINCON_1" to 12, "RINCON_2" to 8),
        favoriteId = "84",
    )

    @Test fun `a preset survives a restart`() {
        val prefs = FakePreferences()
        PresetStore(prefs).add(dinner)
        assertEquals(listOf(dinner), PresetStore(prefs).presets.value)
    }

    @Test fun `saving one again replaces it rather than listing it twice`() {
        val store = PresetStore(FakePreferences())
        store.add(dinner)
        store.add(dinner.copy(name = "renamed"))
        assertEquals(listOf("renamed"), store.presets.value.map { it.name })
    }

    @Test fun `deleting one leaves the others`() {
        val store = PresetStore(FakePreferences())
        store.add(dinner)
        store.add(dinner.copy(id = "b"))
        store.remove("a")
        assertEquals(listOf("b"), store.presets.value.map { it.id })
    }

    @Test fun `an unreadable store is empty rather than a crash on launch`() {
        val prefs = FakePreferences().apply { putString("presets", "{not json") }
        assertEquals(emptyList<Preset>(), PresetStore(prefs).presets.value)
    }
}
