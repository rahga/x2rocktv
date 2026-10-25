package com.rahga.x2rock.viewmodel

import com.rahga.x2rock.lan.SonosHousehold
import com.rahga.x2rock.store.AppleMusicSetting

/**
 * What Music Services and Search name below the services they list (owner, 2026-10-25): the services
 * this household has that this app cannot reach, and those it can once they are switched on in
 * Settings. Without it a service the household uses — YouTube Music always, every service needing an
 * account where the stored sign-ins cannot be read — was simply absent, with no word why.
 */
data class ServiceReach(
    /** Names of the services this household is known to have that are not listed: see [SonosHousehold.unreachableServices]. */
    val unavailable: List<String> = emptyList(),
    /** Apple Music, while it is not listed and Settings → Advanced is where it is turned on. */
    val appleInSettings: Boolean = false,
)

/** Read best-effort: a read that fails leaves its part out rather than saying something untrue. */
suspend fun serviceReach(household: SonosHousehold, appleMusic: AppleMusicSetting): ServiceReach = ServiceReach(
    unavailable = attempt { household.unreachableServices() }.getOrDefault(emptyList()).map { it.name },
    appleInSettings = attempt { !appleMusic.shown() }.getOrDefault(false),
)
