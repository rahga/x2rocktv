package com.rahga.x2rock.viewmodel

import androidx.lifecycle.SavedStateHandle
import com.rahga.x2rock.apple.ITunesSearch
import com.rahga.x2rock.lan.FakePlayer
import com.rahga.x2rock.lan.fakeHousehold
import com.rahga.x2rock.store.AppleMusicSetting
import com.rahga.x2rock.store.PreferredServices
import com.rahga.x2rock.store.PrimaryAccounts
import com.rahga.x2rock.store.RecentSearches
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Search's term outlives the process: a screen Android restores after reclaiming the app opened on
 * an empty field (P-93). Nothing is searched here, so nothing connects.
 */
class SearchRestoreTest {
    @get:Rule val mainDispatcher = MainDispatcherRule()

    private val fake = FakePlayer().also { it.start() }
    private val scope = CoroutineScope(SupervisorJob())
    private val household = fakeHousehold(fake, scope)

    @After fun tearDown() {
        scope.cancel()
        fake.shutdown()
    }

    private fun search(saved: SavedStateHandle): SearchViewModel {
        val prefs = FakePreferences()
        return SearchViewModel(
            household, ITunesSearch(OkHttpClient()), saved, PrimaryAccounts(prefs), PreferredServices(prefs),
            RecentSearches(prefs), AppleMusicSetting(prefs, household),
        )
    }

    @Test fun `a restored search opens on the term typed before`() {
        val saved = SavedStateHandle(mapOf("groupId" to "RINCON_000000000000001400:1"))
        search(saved).setQuery("ryo fukui")

        // Only what was saved, in a handle of its own, as Android hands it to the screen it rebuilds.
        val restored = SavedStateHandle(saved.keys().associateWith { saved.get<Any>(it) })
        assertEquals("ryo fukui", search(restored).query.value)
    }
}
