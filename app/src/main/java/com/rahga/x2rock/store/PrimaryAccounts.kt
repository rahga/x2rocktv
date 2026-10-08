package com.rahga.x2rock.store

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.rahga.x2rock.smapi.LinkedService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Which account of a service this device leads with, where the household has more than one —
 * two Audible accounts owning different books, say. The Sonos app's "Primary Account" is the same
 * idea and is the app's own, not the household's ("Prioritize this account in your app"): making
 * another account primary there changed nothing any player reports (2026-10-08). So it is kept
 * here, per service, by the account's selector — the stable part of its record (`139f5452`),
 * where its `sn_` serial is not.
 */
@Singleton
class PrimaryAccounts @Inject constructor(private val prefs: Preferences) {

    private val gson = Gson()
    private val _primaries = MutableStateFlow(load())
    /** Service id to the primary account's [accountKey]. */
    val primaries: StateFlow<Map<String, String>> = _primaries.asStateFlow()

    fun makePrimary(linked: LinkedService) {
        val next = _primaries.value + (linked.service.id to linked.accountKey())
        prefs.putString(KEY, gson.toJson(next))
        _primaries.value = next
    }

    /** Anything unreadable is dropped rather than crashing the launch: this is an ordering. */
    private fun load(): Map<String, String> = runCatching {
        val json = prefs.getString(KEY) ?: return emptyMap()
        gson.fromJson<Map<String, String>>(json, object : TypeToken<Map<String, String>>() {}.type).orEmpty()
    }.getOrDefault(emptyMap())

    private companion object {
        const val KEY = "primary_accounts"
    }
}

/** What names one account of a service across relinks: its selector, else its serial. */
fun LinkedService.accountKey(): String = selector.ifEmpty { accountId.orEmpty() }

/** Whether [linked] is its service's primary account under [primaries]. */
fun isPrimary(linked: LinkedService, primaries: Map<String, String>): Boolean =
    primaries[linked.service.id] == linked.accountKey()

/**
 * [services] with each service's primary account first among its own and the order otherwise as
 * it was — Sonos's, alphabetical by service.
 */
fun withPrimariesFirst(services: List<LinkedService>, primaries: Map<String, String>): List<LinkedService> {
    val firstIndex = services.withIndex().groupBy({ it.value.service.id }, { it.index }).mapValues { it.value.min() }
    return services.withIndex().sortedWith(
        compareBy({ firstIndex.getValue(it.value.service.id) }, { !isPrimary(it.value, primaries) }, { it.index })
    ).map { it.value }
}

/**
 * The accounts a search across every service asks: a service's primary alone where one is set
 * and still stored, else every account as before. Two accounts of a streaming service search one
 * catalogue, so asking both doubled the requests and listed the hits twice; for a purchase-based
 * one the primary's results are the ones it can play.
 */
fun searchedAccounts(services: List<LinkedService>, primaries: Map<String, String>): List<LinkedService> =
    services.filter { linked ->
        val primary = primaries[linked.service.id] ?: return@filter true
        val stillStored = services.any { it.service.id == linked.service.id && it.accountKey() == primary }
        !stillStored || linked.accountKey() == primary
    }
