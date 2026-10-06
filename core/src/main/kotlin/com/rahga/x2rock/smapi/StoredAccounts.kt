package com.rahga.x2rock.smapi

import com.rahga.x2rock.lan.Xml
import org.w3c.dom.Element
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Reading the music-service tokens a household already stores.
 *
 * Every zone player keeps, per configured music-service account, the same `authToken`/`key`
 * pair a device link mints — the credential some services (Qobuz, and every app-link service
 * whose exchange completes in Sonos's cloud) never hand back through a browser flow. It is
 * not out of reach: the player publishes the whole set, encrypted, in the **initial
 * `ZoneGroupTopology` GENA event** under a variable named `ThirdPartyMediaServersX`, and the
 * key is derived from the household id — LAN metadata any player answers unauthenticated. So
 * this is the missing half of the discovery/playback split: playback rides the household's
 * registration, and search/browse can ride the household's own stored token, with no browser.
 *
 * The mechanism was published by [SoCo PR #1010](https://github.com/SoCo/SoCo/pull/1010) and
 * is a direct port of x2rock's `sonos/stored.rs`, which verified it byte-for-byte against a
 * real household. The MD5 here is protocol, not a security choice: the desktop controller
 * does the same, and the four-byte digest tail is only how a correct decrypt is recognised.
 *
 * **Two halves, deliberately separated.** [decryptAccounts] is pure — bytes and a household
 * id in, accounts out — and carries the whole test suite. Capturing the envelope off a player
 * is the only part that touches the network and lives in [AccountCapture]; the pure half
 * never cares where the string came from.
 */
object StoredAccounts {

    /**
     * The fixed salt Sonos mixes with the household id to derive the blob key. A
     * reverse-engineered constant, public in SoCo #1010 and identical in every household; a
     * firmware change would make the integrity check below start failing and say so, rather
     * than returning garbage — the `2:` prefix is what pins the format.
     */
    private val SALT = byteArrayOf(
        0x1a, 0x01, 0xa7.toByte(), 0x31, 0xc9.toByte(), 0x6e, 0x9e.toByte(), 0xbd.toByte(),
        0xe8.toByte(), 0x47, 0x51, 0x82.toByte(), 0xb2.toByte(), 0x74, 0xb7.toByte(), 0x0e,
    )

    /**
     * Decrypt the `ThirdPartyMediaServersX` envelope into the accounts it holds.
     *
     * [householdId] is the **short** form (`Sonos_xxxxx`, no `.suffix`), as `GetHouseholdID`
     * returns it — not the long form the SMAPI/Control header wants. A wrong household id
     * fails the integrity check rather than returning nonsense.
     */
    fun decryptAccounts(encoded: String, householdId: String): List<StoredAccount> =
        parseAccounts(decryptPayload(encoded, householdId))

    /**
     * Every element of the decrypted payload, as its tag and raw attributes — for *looking*
     * at a household's records, including attributes nothing models yet. Secrets come back
     * intact: the caller that prints them decides what a terminal may see.
     */
    fun decryptElements(encoded: String, householdId: String): List<StoredRecord> {
        val doc = Xml.parse(String(decryptPayload(encoded, householdId), Charsets.UTF_8))
        return doc.descendants()
            .filter { it.hasAttribute("UDN") }
            .map { node ->
                StoredRecord(
                    tag = node.tagName.substringAfter(':'),
                    attributes = (0 until node.attributes.length).map {
                        val a = node.attributes.item(it)
                        a.nodeName to a.nodeValue
                    },
                )
            }
    }

    /**
     * The `2:`-prefixed, base64'd `iv + AES-128-CBC(ciphertext)` envelope, decoded and
     * verified, returning the account XML bytes.
     */
    private fun decryptPayload(encoded: String, householdId: String): ByteArray {
        val body = encoded.trim().removePrefix("2:").also {
            require(it != encoded.trim()) { "unexpected account envelope version (want a `2:` prefix)" }
        }
        val raw = runCatching { Base64.getDecoder().decode(body) }
            .getOrElse { throw IllegalArgumentException("account envelope was not valid base64") }
        require(raw.size >= 32 && (raw.size - 16) % 16 == 0) {
            "account envelope is the wrong size to be iv + AES blocks"
        }
        val iv = raw.copyOfRange(0, 16)
        val ciphertext = raw.copyOfRange(16, raw.size)

        // key = md5(iv + md5(household + salt))
        val global = md5(householdId.toByteArray(Charsets.UTF_8), SALT)
        val blobKey = md5(iv, global)
        val plain = Cipher.getInstance("AES/CBC/NoPadding").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(blobKey, "AES"), IvParameterSpec(iv))
            doFinal(ciphertext)
        }

        // PKCS#7: the last byte is the pad length, 1..=16.
        val pad = (plain.last().toInt() and 0xff)
        require(pad in 1..16 && pad <= plain.size) {
            "wrong household id, or a corrupt account payload (bad PKCS#7 padding)"
        }
        val unpadded = plain.copyOfRange(0, plain.size - pad)

        // The plaintext ends with the first four bytes of md5(payload): the integrity tail
        // that tells a correct decrypt from a wrong key.
        require(unpadded.size >= 4) { "decrypted account payload is too short to carry its checksum" }
        val payload = unpadded.copyOfRange(0, unpadded.size - 4)
        val checksum = unpadded.copyOfRange(unpadded.size - 4, unpadded.size)
        require(md5(payload).copyOfRange(0, 4).contentEquals(checksum)) {
            "account payload integrity check failed — wrong household id, or not this format"
        }
        return payload
    }

    /**
     * Parse the decrypted account XML. Each element with a `UDN` of `SA_RINCON<type>_...` is
     * one service, where `type / 256` is the service id. A service with two accounts has been
     * seen served as two elements, not as `Token0`/`Token1` on one; the index walk costs four
     * lines and makes that question moot either way. A run of indices stops early at an entry
     * with no token or serial rather than padding the list with empty records.
     */
    private fun parseAccounts(payload: ByteArray): List<StoredAccount> {
        val doc = Xml.parse(String(payload, Charsets.UTF_8))
        val accounts = mutableListOf<StoredAccount>()
        for (node in doc.descendants()) {
            val udn = node.getAttribute("UDN")
            if (udn.isEmpty()) continue
            val encodedType = udn.removePrefix("SA_RINCON").takeIf { it != udn }
                ?.substringBefore('_')?.toUIntOrNull()?.toLong() ?: continue
            val count = node.getAttribute("NumAccounts").toIntOrNull() ?: 1
            var i = 0
            while (i < count) {
                fun at(name: String): String? = node.getAttribute("$name$i").ifEmpty { null }
                // An index the element claims but does not carry is the end of the real run.
                if (i > 0 && at("Token") == null && at("SerialNum") == null) break
                accounts += StoredAccount(
                    serviceId = encodedType / 256,
                    serial = at("SerialNum")?.toIntOrNull() ?: 0,
                    token = at("Token").orEmpty(),
                    key = at("Key").orEmpty(),
                    nickname = at("Nickname").orEmpty(),
                    tier = at("Tier").orEmpty(),
                    accountKey = at("Username")?.let(::accountKeyIn).orEmpty(),
                    flags = at("Flags").orEmpty(),
                )
                i++
            }
        }
        return accounts
    }

    /**
     * The account key inside a `Username` value: `X_#Svc<type>-<key>-Token`. Anything not that
     * shape is handed back whole — it is a display-and-keying aid, better carried verbatim
     * than silently emptied.
     */
    internal fun accountKeyIn(username: String): String {
        val head = username.substringBeforeLast("-Token", missingDelimiterValue = "")
        if (head.isEmpty() || !head.contains('-')) return username
        return head.substringAfterLast('-')
    }

    private fun md5(vararg parts: ByteArray): ByteArray =
        MessageDigest.getInstance("MD5").apply { parts.forEach(::update) }.digest()
}

/**
 * One music-service account as the household stores it. The [token]/[key] pair is what goes
 * into the SMAPI `loginToken` header; everything else names the account and matches it to a
 * service.
 */
data class StoredAccount(
    /** The Sonos service id, decoded from the account UDN (`type / 256`). */
    val serviceId: Long,
    /** `SerialNum<i>` — the household's own selector for this account, the `sn_N` seen elsewhere. */
    val serial: Int,
    /** `authToken`. Secret. */
    val token: String,
    /** `privateKey`. Secret; empty for some services, which is legitimate. */
    val key: String,
    /** `Nickname<i>`, what the person or app named the account. Often empty. */
    val nickname: String,
    /** `Tier<i>`, a service-specific tier marker. Kept only for display. */
    val tier: String,
    /** The account's own key, out of `Username<i>` — an account selector, not a secret. */
    val accountKey: String,
    /** `Flags<i>`, unparsed. `4` where `Username<i>` carries a real key, `0` otherwise. */
    val flags: String,
) {
    /**
     * Whether this record actually carries a credential. An account can be listed with an
     * empty token (an anonymous service's placeholder, or a half-removed account); such a row
     * is real but useless to inject.
     */
    val hasToken: Boolean get() = token.isNotEmpty()
}

/** One element of the decrypted payload, as its tag and its attributes in order. */
data class StoredRecord(val tag: String, val attributes: List<Pair<String, String>>)

/** Every element descendant of this one, in document order. */
private fun Element.descendants(): List<Element> {
    val all = getElementsByTagName("*")
    return (0 until all.length).mapNotNull { all.item(it) as? Element }
}
