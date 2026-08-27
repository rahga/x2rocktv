package com.rahga.x2rock.cli

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission

class FileTokenStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `round-trips through disk so a second process sees the rotated token`() {
        val file = tmp.root.toPath().resolve("cfg/tokens.json")
        val first = FileTokenStore(file)
        first.accessToken = "a1"
        first.refreshToken = "r1"
        first.expiresAt = 123L
        first.pendingAuthState = "st"
        first.pendingRedirectUri = "x2rock://callback"

        val second = FileTokenStore(file)
        assertEquals("a1", second.accessToken)
        assertEquals("r1", second.refreshToken)
        assertEquals(123L, second.expiresAt)
        assertEquals("st", second.pendingAuthState)
        assertEquals("x2rock://callback", second.pendingRedirectUri)
        assertTrue(second.isAuthenticated)
    }

    @Test
    fun `the file and its directory are private to the user`() {
        val file = tmp.root.toPath().resolve("cfg/tokens.json")
        FileTokenStore(file).accessToken = "secret"

        assertEquals(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), Files.getPosixFilePermissions(file))
        val dirPerms = Files.getPosixFilePermissions(file.parent)
        assertFalse(dirPerms.any { it.name.startsWith("GROUP") || it.name.startsWith("OTHERS") })
    }

    @Test
    fun `clear removes the file entirely rather than leaving empty fields`() {
        val file = tmp.root.toPath().resolve("tokens.json")
        val store = FileTokenStore(file)
        store.accessToken = "a"
        store.clear()
        assertFalse(Files.exists(file))
        assertNull(store.accessToken)
        assertFalse(store.isAuthenticated)
    }

    @Test
    fun `a corrupt file starts from empty instead of crashing`() {
        val file = tmp.root.toPath().resolve("tokens.json")
        Files.writeString(file, "{ not json")
        val store = FileTokenStore(file)
        assertNull(store.accessToken)
        assertFalse(store.isAuthenticated)
    }

    @Test
    fun `a missing file is simply signed out`() {
        val store = FileTokenStore(tmp.root.toPath().resolve("nope/tokens.json"))
        assertFalse(store.isAuthenticated)
    }
}
