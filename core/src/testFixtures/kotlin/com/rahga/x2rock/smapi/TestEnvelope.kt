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
 * `iv + AES-128-CBC`, base64'd under a `2:` prefix. The SALT is the same protocol constant the
 * decrypt uses — a round trip through both is what proves the decrypt right.
 */
object TestEnvelope {

    private val SALT = byteArrayOf(
        0x1a, 0x01, 0xa7.toByte(), 0x31, 0xc9.toByte(), 0x6e, 0x9e.toByte(), 0xbd.toByte(),
        0xe8.toByte(), 0x47, 0x51, 0x82.toByte(), 0xb2.toByte(), 0x74, 0xb7.toByte(), 0x0e,
    )

    /** Seal [accountXml] for [householdId] (the short `Sonos_xxx` form), with a fixed-byte IV. */
    fun seal(accountXml: String, householdId: String, iv: ByteArray = ByteArray(16) { 4 }): String {
        val blobKey = md5(iv, md5(householdId.toByteArray(), SALT))
        val body = accountXml.toByteArray() + md5(accountXml.toByteArray()).copyOfRange(0, 4)
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
