package com.rahga.x2rock.lan

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A household with Sonos's **Authentication** on refuses every command from a client that cannot say
 * who it is; a signed-in account's token in each command's header is what it accepts (T-27).
 */
class AuthenticationTest {

    private lateinit var fake: FakePlayer
    private lateinit var scope: CoroutineScope

    @Before fun setUp() {
        fake = FakePlayer().also { it.start() }
        scope = CoroutineScope(SupervisorJob())
    }

    @After fun tearDown() {
        scope.cancel()
        fake.shutdown()
    }

    @Test fun `a signed-in token rides in every command's header, and the household answers`() = runBlocking {
        fake.requiredBearer = "t-1"
        val household = fakeHousehold(fake, scope, authorization = { "t-1" })
        household.connect(fake.seed)
        withTimeout(5_000) { household.state.first { it.connected && it.groups.isNotEmpty() } }
        assertEquals("Bearer t-1", fake.lastAuthorization)
        household.disconnect()
    }

    @Test fun `without one, such a household lets nothing through`() = runBlocking {
        fake.requiredBearer = "t-1"
        val household = fakeHousehold(fake, scope)
        runCatching { household.connect(fake.seed) }
        val connected = withTimeoutOrNull(3_000) { household.state.first { it.connected && it.groups.isNotEmpty() } }
        assertNull("no groups without a token", connected)
        assertNull("and none was sent", fake.lastAuthorization)
        household.disconnect()
    }

    @Test fun `a household with Authentication off is never sent a token by a device not signed in`() = runBlocking {
        val household = fakeHousehold(fake, scope, authorization = { null })
        household.connect(fake.seed)
        withTimeout(5_000) { household.state.first { it.connected && it.groups.isNotEmpty() } }
        assertNull(fake.lastAuthorization)
        household.disconnect()
    }
}
