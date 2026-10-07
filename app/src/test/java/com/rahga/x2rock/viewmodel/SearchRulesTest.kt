package com.rahga.x2rock.viewmodel

import com.rahga.x2rock.smapi.Category
import org.junit.Assert.assertEquals
import org.junit.Test

/** How one search across every service decides what to ask each, and in what order to show them. */
class SearchRulesTest {

    @Test fun `tracks and albums are asked for where a service has them`() {
        // Deezer's real map lists artists first; the order asked for is ours, not the map's.
        val deezer = listOf(Category("artists", "SART"), Category("albums", "SALB"), Category("tracks", "STRK"), Category("playlists", "SPLY"))
        assertEquals(listOf("tracks", "albums"), SearchViewModel.pickCategories(deezer).map { it.id })
    }

    @Test fun `a radio service is asked for its stations`() {
        val radio = listOf(Category("stations", "search:station"), Category("Blogs", "SBLG"))
        assertEquals(listOf("stations"), SearchViewModel.pickCategories(radio).map { it.id })
    }

    @Test fun `a service with only its own categories is asked for its first two`() {
        // Hype Machine's custom category, and nothing standard.
        val custom = listOf(Category("Blogs", "SBLG"), Category("Feeds", "SFED"), Category("More", "SMOR"))
        assertEquals(listOf("Blogs", "Feeds"), SearchViewModel.pickCategories(custom).map { it.id })
    }

    @Test fun `signed-in services come first, then the rest, each by name`() {
        val sections = listOf(
            SearchViewModel.Section("TuneIn", signedIn = false, hits = emptyList(), key = "TuneIn"),
            SearchViewModel.Section("Qobuz", signedIn = true, hits = emptyList(), key = "Qobuz"),
            SearchViewModel.Section("audible", signedIn = true, hits = emptyList(), key = "audible"),
            SearchViewModel.Section("Calm Radio", signedIn = false, hits = emptyList(), key = "Calm Radio"),
        )
        assertEquals(
            listOf("audible", "Qobuz", "Calm Radio", "TuneIn"),
            sections.sortedWith(SearchViewModel.sectionOrder).map { it.name },
        )
    }
}
