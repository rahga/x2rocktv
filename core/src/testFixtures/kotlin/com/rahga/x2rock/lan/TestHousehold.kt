package com.rahga.x2rock.lan

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient

/** Connect to [fake] as its seed and wait until the household says it is connected. */
suspend fun SonosHousehold.connectTo(fake: FakePlayer, timeoutMillis: Long = 5_000): HouseholdState {
    connect(fake.seed)
    return withTimeout(timeoutMillis) { state.first { it.connected } }
}

/** A seed store over one field, which a test reads back as [held]. */
class FakeSeedStore(
    override val keyedByNetwork: Boolean = false,
    var held: Discovery.DiscoveredPlayer? = null,
) : SeedStore {
    override fun load() = held
    override fun save(player: Discovery.DiscoveredPlayer) { held = player }
    override fun clear() { held = null }
}

/**
 * A household pointed at [fake], with what every test wants fixed: no multicast, the production
 * client over [addressBook], and **no real SSDP or mDNS** — a test that dropped its connection used to
 * reconnect through a sweep of this machine's actual LAN (two did, until 2026-10-10) — and no GENA
 * subscribe to a player for its accounts, which a test wanting them passes as [accountCapture]. The
 * rest are the constructor's own parameters, defaulted as it defaults them; name only what a test
 * changes.
 */
fun fakeHousehold(
    fake: FakePlayer,
    scope: CoroutineScope,
    addressBook: PlayerAddressBook = PlayerAddressBook(),
    upnpPort: Int = Upnp.PORT,
    seeds: SeedStore = SeedStore.None,
    settleMillis: Long = UNANSWERED_SETTLE_MILLIS,
    ssdp: suspend () -> List<Discovery.DiscoveredPlayer> = { emptyList() },
    mdns: MdnsDiscovery = MdnsDiscovery.None,
    internetClient: OkHttpClient = OkHttpClient(),
    accountCapture: suspend (String) -> String? = { null },
    authorization: (suspend () -> String?)? = null,
    servicePages: com.rahga.x2rock.smapi.ServicePages? = null,
    speakerSalt: ByteArray? = com.rahga.x2rock.smapi.TestEnvelope.SALT,
): SonosHousehold = SonosHousehold(
    scope = scope,
    addressBook = addressBook,
    multicast = MulticastGate.None,
    mdns = mdns,
    client = LanHttp.client(addressBook),
    seeds = seeds,
    internetClient = internetClient,
    port = fake.port,
    upnpPort = upnpPort,
    settleMillis = settleMillis,
    ssdp = ssdp,
    accountCapture = accountCapture,
    authorization = authorization,
    servicePages = servicePages,
    speakerSalt = speakerSalt,
)
