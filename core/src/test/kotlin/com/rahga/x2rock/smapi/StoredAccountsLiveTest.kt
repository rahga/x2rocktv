package com.rahga.x2rock.smapi

import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.net.URL

/**
 * Opt-in, against a real player: capture the household's `ThirdPartyMediaServersX` and decrypt
 * it, the way the app will on a TV. Skipped unless a player's IP is named, like the live suite:
 *
 * ```
 * ./gradlew :core:test -Dx2rock.capture=192.168.1.20 --tests '*StoredAccountsLiveTest'
 * ```
 *
 * It needs the player to open a connection back to this machine (TCP, an ephemeral port), so a
 * host firewall must allow it. **It prints only byte lengths, never a token** — the same
 * masking x2rock's `--from-household --dry-run` uses — and changes nothing on the household: a
 * topology subscription is a silent read. It proves the capture and the decrypt against real
 * data, which the round-trip unit tests cannot.
 */
class StoredAccountsLiveTest {

    private val player: String? = System.getProperty("x2rock.capture")?.takeIf { it.isNotBlank() }

    @Before fun requireHardware() {
        assumeTrue("set -Dx2rock.capture=<player-ip> to run against a real player", player != null)
    }

    @Test fun `the household's own tokens capture and decrypt`() = runBlocking {
        val ip = player!!
        val statusZp = URL("http://$ip:1400/status/zp").readText()
        val longId = Regex("<HouseholdControlID>([^<]+)</HouseholdControlID>").find(statusZp)!!.groupValues[1]
        val shortId = longId.substringBefore('.')

        val envelope = AccountCapture.captureEnvelope(ip, timeoutMillis = 15_000)
        requireNotNull(envelope) { "no account event arrived — is inbound TCP allowed from the player?" }
        val accounts = StoredAccounts.decryptAccounts(envelope, shortId)

        println("Household ${shortId}: ${accounts.size} account records")
        accounts.forEach { a ->
            val name = a.nickname.ifEmpty { "sn_${a.serial}" }
            println("  service ${a.serviceId} sn_${a.serial} \"$name\": " +
                "token ${a.token.length} / key ${a.key.length} bytes")
        }
        assert(accounts.any { it.hasToken }) { "a real household should hold at least one usable token" }
    }

    /**
     * The whole credentialed path against real hardware: capture, decrypt, resolve the services,
     * and search one with the household's own token. Prints only the result count and a few
     * titles — public catalogue data, never the token. Picks a credentialed service so the
     * `loginToken` header is actually exercised; skips cleanly if the household has none linked.
     */
    @Test fun `a household token searches its service end to end`() = runBlocking {
        val ip = player!!
        val longId = Regex("<HouseholdControlID>([^<]+)</HouseholdControlID>")
            .find(URL("http://$ip:1400/status/zp").readText())!!.groupValues[1]
        val envelope = requireNotNull(AccountCapture.captureEnvelope(ip, 15_000)) { "no account event arrived" }
        val accounts = StoredAccounts.decryptAccounts(envelope, longId.substringBefore('.'))

        // Upnp addresses players by .local name only; a test on a bare IP posts the SOAP itself.
        val listReply = OkHttpClient().newCall(
            Request.Builder()
                .url("http://$ip:1400/MusicServices/Control")
                .header("SOAPACTION", "\"urn:schemas-upnp-org:service:MusicServices:1#ListAvailableServices\"")
                .post((
                    "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
                        "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>" +
                        "<u:ListAvailableServices xmlns:u=\"urn:schemas-upnp-org:service:MusicServices:1\"/>" +
                        "</s:Body></s:Envelope>"
                    ).toRequestBody("text/xml; charset=utf-8".toMediaType()))
                .build(),
        ).execute().use { it.body!!.string() }
        // The DOM unescapes the once-escaped inner XML exactly once, which is what parseServices wants.
        val reply = com.rahga.x2rock.lan.Xml.parse(listReply)
        val nodes = reply.getElementsByTagName("*")
        fun field(name: String): String = (0 until nodes.length)
            .mapNotNull { nodes.item(it) as? org.w3c.dom.Element }
            .firstOrNull { it.tagName.substringAfter(':') == name }?.textContent.orEmpty()
        val services = parseServices(field("AvailableServiceDescriptorList"), field("AvailableServiceTypeList"))
        val linked = linkedServices(services, accounts, longId)
        val credentialed = linked.filter { it.token != null && it.service.auth != Auth.ANONYMOUS }
        assumeTrue("this household has no credentialed service linked", credentialed.isNotEmpty())

        // Prove the path on whichever credentialed service answers cleanly. Some are finicky
        // (Amazon Music answers HTTP 500 to a search here, as x2rock also found), so the first
        // that returns hits is enough; each service's own result is printed for the record.
        val client = SmapiClient(OkHttpClient())
        var any = false
        for (svc in credentialed) {
            val categories = runCatching { client.categories(svc.service) }.getOrDefault(emptyList())
            if (categories.isEmpty()) { println("${svc.service.name}: no search categories"); continue }
            val category = categories.firstOrNull { it.id.contains("track", ignoreCase = true) } ?: categories.first()
            val page = runCatching { client.search(svc.service, svc.token, category.mappedId, "miles davis") }
            page.onSuccess { p ->
                println("${svc.service.name} '${category.id}' for 'miles davis': ${p.total} total, ${p.items.size} shown")
                p.items.take(3).forEach { println("    ${it.title} — ${it.summary ?: ""} [${it.itemType}]") }
                if (p.items.isNotEmpty()) any = true
            }.onFailure { println("${svc.service.name}: ${it.message}") }
        }
        assert(any) { "at least one credentialed service should return hits for 'miles davis'" }
    }
}
