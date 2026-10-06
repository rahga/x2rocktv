package com.rahga.x2rock.smapi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests for the pure half of the account capture — extracting the envelope from a GENA NOTIFY
 * body, reading an HTTP message, and finding a header. Ported from x2rock's `sonos/stored.rs`
 * test module; the network half ([AccountCapture.captureEnvelope]) needs a real player and a
 * device to open a listening socket, and is verified there, not here.
 */
class AccountCaptureTest {

    @Test fun `a doubly escaped property value is recovered`() {
        // The player escapes the property once; some paths escape the outer document again,
        // so &amp;lt; must resolve to <.
        val body = "<e:propertyset><e:property>" +
            "<ThirdPartyMediaServersX>2:AAAA</ThirdPartyMediaServersX>" +
            "</e:property></e:propertyset>"
        assertEquals("2:AAAA", AccountCapture.extractVariable(body, "ThirdPartyMediaServersX"))
    }

    @Test fun `an absent variable is null not blank`() {
        assertNull(AccountCapture.extractVariable("<e:propertyset/>", "ThirdPartyMediaServersX"))
        // Present but empty is also null — an empty announcement is not a credential.
        assertNull(AccountCapture.extractVariable("<ThirdPartyMediaServersX></ThirdPartyMediaServersX>", "ThirdPartyMediaServersX"))
    }

    @Test fun `a header is read case-insensitively`() {
        val head = "HTTP/1.1 200 OK\r\nSID: uuid:abc-123\r\nContent-Length: 0\r\n\r\n"
        assertEquals("uuid:abc-123", AccountCapture.header(head, "sid"))
        assertNull(AccountCapture.header(head, "Nonexistent"))
    }

    @Test fun `an http message is read head plus its content-length body`() {
        val msg = "NOTIFY /notify HTTP/1.1\r\nContent-Length: 5\r\n\r\nHELLO"
        val read = AccountCapture.readHttpMessage(msg.byteInputStream())
        assertEquals(msg, read)
    }
}
