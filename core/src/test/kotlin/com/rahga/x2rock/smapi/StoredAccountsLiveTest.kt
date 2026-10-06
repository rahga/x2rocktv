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

    /**
     * Every service the household unlocks, search and browse, one line each — the full matrix, so
     * "tested against all services" is a thing the output actually shows rather than a claim. Opt-in
     * on the capture IP; read-only (a search term and a `getMetadata` of root change nothing).
     */
    @Test fun `every service answers search and browse`() = runBlocking {
        val ip = player!!
        val longId = Regex("<HouseholdControlID>([^<]+)</HouseholdControlID>")
            .find(URL("http://$ip:1400/status/zp").readText())!!.groupValues[1]
        val envelope = requireNotNull(AccountCapture.captureEnvelope(ip, 15_000)) { "no account event" }
        val accounts = StoredAccounts.decryptAccounts(envelope, longId.substringBefore('.'))
        val answer = soapListServices(ip)
        val services = parseServices(answer.first, answer.second)
        val linked = linkedServices(services, accounts, longId)
        val client = SmapiClient(OkHttpClient())

        println("service matrix (${linked.size} usable of ${services.size} in the catalogue):")
        var searched = 0; var browsed = 0
        for (svc in linked) {
            val name = svc.service.name + (if (svc.nickname.isNotEmpty()) " (${svc.nickname})" else "")
            val cats = runCatching { client.categories(svc.service) }.getOrDefault(emptyList())
            val search = if (cats.isEmpty()) "no categories" else runCatching {
                val cat = cats.firstOrNull { it.id.contains("track", true) } ?: cats.first()
                val p = client.search(svc.service, svc.token, cat.mappedId, "jazz")
                if (p.items.isNotEmpty()) searched++
                "search '${cat.id}' -> ${p.total} total, ${p.items.size} shown"
            }.getOrElse { "search FAILED: ${it.message?.take(60)}" }
            val browse = runCatching {
                val p = client.metadata(svc.service, svc.token, "root")
                if (p.items.isNotEmpty()) browsed++
                "browse -> ${p.items.size} items"
            }.getOrElse { "browse FAILED: ${it.message?.take(60)}" }
            println("  %-28s | %-42s | %s".format(name.take(28), search.take(42), browse))
        }
        println("searched ok: $searched, browsed ok: $browsed, of ${linked.size}")
        assert(linked.isNotEmpty())
    }

    /**
     * A focused look at one service by name, with a term that suits it — the matrix searches "jazz",
     * which names no audiobook. Opt-in: `-Dx2rock.capture=<ip> -Dx2rock.probe=Audible -Dx2rock.term="Dune"`.
     */
    @Test fun `probe one service by name`() = runBlocking {
        val ip = player!!
        val name = System.getProperty("x2rock.probe")
        assumeTrue("set -Dx2rock.probe=<service name> to probe one service", name != null)
        val term = System.getProperty("x2rock.term") ?: "Dune"
        val longId = Regex("<HouseholdControlID>([^<]+)</HouseholdControlID>")
            .find(URL("http://$ip:1400/status/zp").readText())!!.groupValues[1]
        val envelope = requireNotNull(AccountCapture.captureEnvelope(ip, 15_000))
        val accounts = StoredAccounts.decryptAccounts(envelope, longId.substringBefore('.'))
        val answer = soapListServices(ip)
        val linked = linkedServices(parseServices(answer.first, answer.second), accounts, longId)
        val svc = linked.first { it.service.name.contains(name!!, ignoreCase = true) }
        val client = SmapiClient(OkHttpClient())

        println("${svc.service.name} (sid ${svc.service.id}, type ${svc.service.serviceType}):")
        val cats = client.categories(svc.service)
        println("  categories: ${cats.joinToString { "${it.id}->${it.mappedId}" }}")
        for (cat in cats) {
            val p = runCatching { client.search(svc.service, svc.token, cat.mappedId, term) }.getOrNull()
            if (p == null) {
                println("  search ${cat.id}: FAILED")
                continue
            }
            println("  search '${cat.id}' for '$term': ${p.total} total")
            p.items.take(3).forEach { println("      ${it.title} — ${it.summary ?: ""} [${it.itemType}]${if (it.container) " ›" else ""}") }
        }
        val root = client.metadata(svc.service, svc.token, "root")
        println("  browse root: ${root.items.map { "${it.title}[${it.itemType}]" }}")
        // One level in, to see the audiobook shape.
        root.items.firstOrNull { it.container }?.let { c ->
            val inside = runCatching { client.metadata(svc.service, svc.token, c.id) }.getOrNull()
            println("  into '${c.title}': ${inside?.items?.take(4)?.map { "${it.title}[${it.itemType}]" }}")
        }
        // The raw getMetadata of the first search hit, to see whether a resume position, chapters or
        // a `canResume`-shaped marker is exposed in the metadata (vs. handled by the service itself).
        var hit: Item? = null
        for (cat in cats) {
            hit = runCatching { client.search(svc.service, svc.token, cat.mappedId, term).items.firstOrNull() }.getOrNull()
            if (hit != null) break
        }
        if (hit != null) {
            println("  first leaf hit: '${hit.title}' [${hit.itemType}] id=${hit.id}")
            val uri = runCatching { client.mediaUri(svc.service, svc.token, hit.id) }
            println("  getMediaURI -> " + uri.fold({ it }, { "FAILED: ${it.message?.take(80)}" }))
            println("  raw getMediaMetadata:")
            println("  " + rawSmapi(svc, "getMediaMetadata", "<id>${hit.id}</id>").take(700))
        }
        Unit
    }

    /** One raw SMAPI call, body returned verbatim — for seeing fields the parser drops. */
    private fun rawSmapi(svc: LinkedService, action: String, params: String): String {
        val ns = "http://www.sonos.com/Services/1.1"
        val login = svc.token?.let {
            "<loginToken><token>${it.token}</token><key>${it.key}</key>" +
                (it.household?.let { h -> "<householdId>$h</householdId>" } ?: "") + "</loginToken>"
        }.orEmpty()
        val envelope = "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\">" +
            "<s:Header><credentials xmlns=\"$ns\"><deviceProvider>Sonos</deviceProvider>$login</credentials></s:Header>" +
            "<s:Body><$action xmlns=\"$ns\">$params</$action></s:Body></s:Envelope>"
        return OkHttpClient().newCall(
            okhttp3.Request.Builder().url(svc.service.uri)
                .header("SOAPACTION", "\"$ns#$action\"").header("User-Agent", "x2rock-tv/1.0")
                .post(envelope.toRequestBody("text/xml; charset=utf-8".toMediaType())).build(),
        ).execute().use { it.body?.string().orEmpty() }
    }

    /**
     * Which services publish `NowPlayingRatings` (thumbs), and whether they are anonymous or
     * credentialed — to settle whether ratings are an account-only feature. Read-only (fetches
     * presentation maps off Sonos's CDN). Opt-in on the capture IP.
     */
    @Test fun `which services publish ratings`() = runBlocking {
        val ip = player!!
        val longId = Regex("<HouseholdControlID>([^<]+)</HouseholdControlID>")
            .find(URL("http://$ip:1400/status/zp").readText())!!.groupValues[1]
        val envelope = requireNotNull(AccountCapture.captureEnvelope(ip, 15_000))
        val accounts = StoredAccounts.decryptAccounts(envelope, longId.substringBefore('.'))
        val answer = soapListServices(ip)
        val linked = linkedServices(parseServices(answer.first, answer.second), accounts, longId)
        val client = SmapiClient(OkHttpClient())

        var anonWithRatings = 0; var credWithRatings = 0; var anon = 0; var cred = 0
        println("ratings by service (${linked.size} usable):")
        for (svc in linked) {
            val kind = if (svc.token == null) "anonymous".also { anon++ } else "credentialed".also { cred++ }
            val matches = runCatching { client.ratings(svc.service) }.getOrDefault(emptyList())
            if (matches.isNotEmpty()) {
                if (svc.token == null) anonWithRatings++ else credWithRatings++
                println("  ${svc.service.name} [$kind]: ${matches.size} rating state(s)")
            }
        }
        println("ratings published by: $anonWithRatings of $anon anonymous, $credWithRatings of $cred credentialed")
    }

    /** `ListAvailableServices` by bare IP (Upnp insists on a .local name; a test posts the SOAP). */
    private fun soapListServices(ip: String): Pair<String, String> {
        val reply = OkHttpClient().newCall(
            okhttp3.Request.Builder()
                .url("http://$ip:1400/MusicServices/Control")
                .header("SOAPACTION", "\"urn:schemas-upnp-org:service:MusicServices:1#ListAvailableServices\"")
                .post(
                    ("<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body>" +
                        "<u:ListAvailableServices xmlns:u=\"urn:schemas-upnp-org:service:MusicServices:1\"/>" +
                        "</s:Body></s:Envelope>")
                        .toRequestBody("text/xml; charset=utf-8".toMediaType()),
                ).build(),
        ).execute().use { it.body!!.string() }
        val re50 = com.rahga.x2rock.lan.Xml.parse(reply)
        val nodes = re50.getElementsByTagName("*")
        fun field(n: String) = (0 until nodes.length).mapNotNull { nodes.item(it) as? org.w3c.dom.Element }
            .firstOrNull { it.tagName.substringAfter(':') == n }?.textContent.orEmpty()
        return field("AvailableServiceDescriptorList") to field("AvailableServiceTypeList")
    }

    /**
     * Capture the envelope and write it to a file, for seeding a debug build (the emulator can't
     * receive the callback). Prints only its length, never its content. Opt-in on top of the IP:
     * `-Dx2rock.capture=<ip> -Dx2rock.capture.out=<path> --tests '*StoredAccountsLiveTest.captureToFile'`.
     */
    @Test fun captureToFile() = runBlocking {
        val out = System.getProperty("x2rock.capture.out")
        assumeTrue("set -Dx2rock.capture.out=<path> to write the envelope out", out != null)
        val envelope = requireNotNull(AccountCapture.captureEnvelope(player!!, 15_000)) { "no account event arrived" }
        java.io.File(out!!).writeText(envelope)
        println("wrote ${envelope.length} base64 chars to $out")
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

        // Browse (getMetadata) too — the read-only path a search term cannot reach. Root of the
        // first service that will answer; its top-level containers are what a browse UI opens on.
        for (svc in credentialed) {
            val root = runCatching { client.metadata(svc.service, svc.token, "root") }.getOrNull() ?: continue
            if (root.items.isEmpty()) continue
            println("${svc.service.name} browse root: ${root.total} items")
            root.items.take(5).forEach {
                println("    ${it.title} [${it.itemType}]${if (it.container) " ›" else ""}")
            }
            assert(root.items.isNotEmpty())
            break
        }
    }
}
