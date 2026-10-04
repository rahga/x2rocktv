package com.rahga.x2rock.lan

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Editing the queue over UPnP. The two browse pages are a SYMFONISK pair's real queue,
 * captured read-only on 2026-10-01: ten tracks at UpdateID 58, asked for six at a time so a
 * second page exists. The SaveQueue reply is not a capture — making one creates a playlist,
 * and this was run in a household being listened to — so it is the standard envelope around
 * the one element x2rock reads from the real reply, `AssignedObjectID`.
 */
class QueueEditTest {

    private lateinit var fake: FakePlayer
    private lateinit var upnp: MockWebServer
    private lateinit var scope: CoroutineScope
    private lateinit var household: SonosHousehold
    /** `action` followed by the request's own fields, in order. */
    private val requests = CopyOnWriteArrayList<Pair<String, Map<String, String>>>()


    private fun fields(body: String): Map<String, String> =
        Regex("<(\\w+)>([^<]*)</\\1>").findAll(body).associate { it.groupValues[1] to it.groupValues[2] }

    @Before fun setUp() {
        fake = FakePlayer().also { it.start() }
        upnp = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val action = soapAction(request)
                    val f = fields(request.body.readUtf8())
                    requests += action to f
                    return when (action) {
                        // Six a time, as the capture was taken; a page past the end is the second.
                        "Browse" -> MockResponse().setBody(
                            FakePlayer.fixtureText(if (f["StartingIndex"] == "0") "Browse.queue.page1.xml" else "Browse.queue.page2.xml")
                        )
                        "SaveQueue" -> MockResponse().setBody(
                            "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body>" +
                                "<u:SaveQueueResponse xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">" +
                                "<AssignedObjectID>SQ:11</AssignedObjectID></u:SaveQueueResponse></s:Body></s:Envelope>"
                        )
                        else -> MockResponse().setBody(EMPTY_SOAP)
                    }
                }
            }
            start(InetAddress.getByName("127.0.0.1"), 0)
        }
        scope = CoroutineScope(SupervisorJob())
        val book = PlayerAddressBook()
        household = SonosHousehold(
            scope = scope, addressBook = book, multicast = MulticastGate.None,
            client = LanHttp.client(book), port = fake.port, upnpPort = upnp.port,
        )
        runBlocking {
            household.connectTo(fake)
        }
        requests.clear()
    }

    @After fun tearDown() {
        household.disconnect()
        scope.cancel()
        fake.shutdown()
        upnp.shutdown()
    }

    private fun group() = household.state.value.groups.first().id

    /** A browse answers at most a page; the rest is asked for from where it stopped. */
    @Test fun `a queue longer than one answer is read to the end`() = runBlocking<Unit> {
        val queue = household.queue(group())
        assertEquals(10, queue.items.size)
        assertEquals("58", queue.updateId)
        assertEquals(listOf("0", "6"), requests.filter { it.first == "Browse" }.map { it.second["StartingIndex"] })
    }

    /** Down inserts one past the target, because the track leaves its own place first. */
    @Test fun `moving a track names where to insert it, and the queue version`() = runBlocking<Unit> {
        household.moveInQueue(group(), from = 2, to = 5)
        val down = requests.last { it.first == "ReorderTracksInQueue" }.second
        assertEquals("2", down["StartingIndex"])
        assertEquals("6", down["InsertBefore"])
        assertEquals("58", down["UpdateID"])

        household.moveInQueue(group(), from = 5, to = 2)
        assertEquals("2", requests.last { it.first == "ReorderTracksInQueue" }.second["InsertBefore"])
    }

    @Test fun `clearing and saving send what the player expects`() = runBlocking<Unit> {
        household.clearQueue(group())
        assertEquals("RemoveAllTracksFromQueue", requests.last().first)

        val id = household.saveQueue(group(), "Kitchen, 1 Oct")
        val save = requests.last { it.first == "SaveQueue" }.second
        assertEquals("Kitchen, 1 Oct", save["Title"])
        assertEquals("an empty ObjectID makes a new playlist rather than overwriting one", "", save["ObjectID"])
        assertEquals("SQ:11", id)
    }
}
