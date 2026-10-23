package com.rahga.x2rock.smapi

import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Sealing account XML into a `ThirdPartyMediaServersX` envelope the way a player does — the
 * inverse of [StoredAccounts.decryptAccounts], for tests that need a real envelope with no live
 * household and no real token. Shared so the one derivation lives in one place; `:core` and `:app`
 * tests both reach it through testFixtures.
 *
 * It mirrors the decrypt exactly: payload + 4-byte `md5` tail, PKCS#7 to the block, then
 * `iv + AES-128-CBC`, base64'd under a `2:` prefix. It seals with
 * the tests' own [SALT], handed to the decrypt the way a build hands it the real one — a round trip
 * through both is what proves the decrypt right.
 */
object TestEnvelope {

    /** A salt of the tests' own; the real one is not part of the GPL-licensed source package. */
    val SALT: ByteArray = ByteArray(16) { (0x30 + it).toByte() }

    /**
     * Seal [accountXml] for [householdId] (the short `Sonos_xxx` form), with a fixed-byte IV. [tail]
     * is the 4-byte integrity tail, the payload's own unless a test wants a wrong one under valid
     * padding — the one case the decrypt's checksum is there for.
     */
    fun seal(
        accountXml: String,
        householdId: String,
        iv: ByteArray = ByteArray(16) { 4 },
        tail: ByteArray = md5(accountXml.toByteArray()).copyOfRange(0, 4),
    ): String {
        val blobKey = md5(iv, md5(householdId.toByteArray(), SALT))
        val body = accountXml.toByteArray() + tail
        val pad = 16 - body.size % 16
        val padded = body + ByteArray(pad) { pad.toByte() }
        val ciphertext = Cipher.getInstance("AES/CBC/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(blobKey, "AES"), IvParameterSpec(iv))
        }.doFinal(padded)
        return "2:" + Base64.getEncoder().encodeToString(iv + ciphertext)
    }

    private fun md5(vararg parts: ByteArray): ByteArray =
        MessageDigest.getInstance("MD5").apply { parts.forEach(::update) }.digest()
}
