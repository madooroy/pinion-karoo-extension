package io.github.madooroy.pinionkaroo

import android.content.Context
import fi.nikosavola.karooext.testing.robolectric.FakeKarooRule
import fi.nikosavola.karooext.testing.robolectric.ble.FakeBle
import fi.nikosavola.karooext.testing.robolectric.ble.FakeBlePeripheral
import fi.nikosavola.karooext.testing.robolectric.ble.bleUuid
import io.github.madooroy.pinionkaroo.PinionProtocol.Parameter
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Drives the BLE client against a simulated gearbox without the Karoo extension in between, to
// cover the link lifecycle and the GATT error paths the field streams cannot reach.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PinionBleClientTest {
    @get:Rule val karoo = FakeKarooRule()

    private val ble = FakeBle(karoo.app)
    private lateinit var gearbox: FakeBlePeripheral
    private lateinit var client: PinionBleClient

    private val values = mapOf(
        Parameter.NUMBER_OF_GEARS to 12L,
        Parameter.CURRENT_GEAR to 5L,
        Parameter.BATTERY_LEVEL to 8750L,
        Parameter.SERIAL_NUMBER to 123456L,
    )
    private var respond: (Parameter) -> ByteArray = { reply(it, values.getValue(it)) }

    private fun reply(parameter: Parameter, value: Long): ByteArray =
        byteArrayOf(0x02, parameter.length.toByte()) + parameter.address +
            ByteArray(parameter.length) { (value shr (8 * it)).toByte() }

    private fun errorReply(parameter: Parameter): ByteArray =
        byteArrayOf(0x00, parameter.length.toByte()) + parameter.address

    private fun replyParameter(request: ByteArray): Parameter =
        Parameter.entries.first { it.address.contentEquals(request.copyOfRange(2, 5)) }

    private fun pinionPeripheral(address: String, name: String? = "Pinion"): FakeBlePeripheral =
        ble.peripheral(address, name = name) {
            service(PinionProtocol.SERVICE_UUID, advertised = true) {
                characteristic(PinionProtocol.CURRENT_GEAR_UUID, notify = true)
                characteristic(PinionProtocol.REQUEST_UUID, write = true)
                characteristic(PinionProtocol.RESPONSE_UUID, indicate = true)
            }
        }

    @Before
    fun setUp() {
        gearbox = pinionPeripheral("AA:BB:CC:DD:EE:01")
        gearbox.onWrite(PinionProtocol.REQUEST_UUID) { request ->
            gearbox.notify(PinionProtocol.RESPONSE_UUID, respond(replyParameter(request)))
        }
        client = PinionBleClient.get(karoo.app)
    }

    @After
    fun resetClient() = PinionClientSingleton.reset()

    private fun awaitLink(link: PinionLink): PinionState =
        karoo.awaitValue { client.state.value.takeIf { it.link == link } }

    private fun startAndConnect() {
        client.start()
        ble.awaitScan()
        gearbox.advertise()
        gearbox.connect()
        gearbox.awaitSubscribed(PinionProtocol.RESPONSE_UUID)
        gearbox.awaitSubscribed(PinionProtocol.CURRENT_GEAR_UUID)
    }

    @Test
    fun `the client is a process-wide singleton`() {
        assertSame(client, PinionBleClient.get(karoo.app))
    }

    @Test
    fun `without the bluetooth permission the link says so and nothing is scanned`() {
        // setUp deliberately does not grant permissions
        client.start()

        awaitLink(PinionLink.NO_PERMISSION)
        assertFalse(ble.isScanning)
    }

    @Test
    fun `bluetooth off is reported and nothing is scanned`() {
        ble.grantPermissions()
        ble.setEnabled(false)

        client.start()

        awaitLink(PinionLink.BLUETOOTH_OFF)
        assertFalse(ble.isScanning)
    }

    @Test
    fun `connecting subscribes to gear and response and adopts the gearbox`() {
        ble.grantPermissions()

        startAndConnect()

        assertEquals(
            setOf(PinionProtocol.CURRENT_GEAR_UUID, PinionProtocol.RESPONSE_UUID),
            gearbox.subscribedCharacteristics,
        )
        assertEquals(gearbox.address, client.savedAddress)
    }

    @Test
    fun `the starting gear, gear count and battery are read on connect`() {
        ble.grantPermissions()

        startAndConnect()

        val state = karoo.awaitValue { client.state.value.takeIf { it.batteryPercent != null } }
        assertEquals(5, state.gear)
        assertEquals(12, state.gearCount)
        assertEquals(87.5, state.batteryPercent!!, 0.0)
    }

    @Test
    fun `a saved gearbox is scanned for by address, not by service`() {
        ble.grantPermissions()
        karoo.app.getSharedPreferences("pinion", Context.MODE_PRIVATE).edit()
            .putString("gearbox_address", gearbox.address).apply()
        val other = pinionPeripheral("AA:BB:CC:DD:EE:02")

        client.start()
        ble.awaitScan()
        other.advertise() // advertises the same service but from a different address
        gearbox.advertise(untilMatched = true)
        gearbox.connect()
        gearbox.awaitSubscribed(PinionProtocol.RESPONSE_UUID)

        assertEquals(0, other.connectionAttempts)
        assertEquals(1, gearbox.connectionAttempts)
    }

    @Test
    fun `a gearbox without the pinion service is retried`() {
        ble.grantPermissions()
        val broken = ble.peripheral("AA:BB:CC:DD:EE:03", name = "Pinion") {
            advertiseService(PinionProtocol.SERVICE_UUID)
            service(bleUuid(0x180F)) { characteristic(bleUuid(0x2A19), read = true) }
        }

        client.start()
        ble.awaitScan()
        broken.advertise(untilMatched = true)
        broken.connect() // service discovery finds no Pinion service and the client drops it

        broken.advertise(untilMatched = true)
        broken.awaitConnectionRequest(10_000).let { broken.accept() }

        assertTrue("attempts=${broken.connectionAttempts}", broken.connectionAttempts >= 2)
    }

    @Test
    fun `a connection failure is retried`() {
        ble.grantPermissions()

        client.start()
        ble.awaitScan()
        gearbox.advertise()
        gearbox.awaitConnectionRequest()
        gearbox.failConnection(133)

        gearbox.advertise(untilMatched = true)
        gearbox.connect()
        gearbox.awaitSubscribed(PinionProtocol.RESPONSE_UUID)

        assertTrue(gearbox.connectionAttempts >= 2)
    }

    @Test
    fun `an error reply leaves the value unread`() {
        ble.grantPermissions()
        respond = { parameter ->
            if (parameter == Parameter.BATTERY_LEVEL) errorReply(parameter) else reply(parameter, values.getValue(parameter))
        }

        startAndConnect()

        val state = karoo.awaitValue { client.state.value.takeIf { it.gear == 5 } }
        assertNull(state.batteryPercent)
    }

    @Test
    fun `the link is kept for a while after the last user leaves`() {
        ble.grantPermissions()

        client.start()
        ble.awaitScan()
        client.stop()

        assertTrue(ble.isScanning) // the linger keeps the job running
        client.start() // a user comes back before the linger expires
        assertTrue(ble.isScanning)
    }

    @Test
    fun `an unexpected disconnect is followed by a reconnect`() {
        ble.grantPermissions()
        startAndConnect()
        karoo.awaitValue { client.state.value.takeIf { it.gear == 5 } }

        gearbox.disconnect()
        awaitLink(PinionLink.SEARCHING)

        gearbox.advertise(untilMatched = true)
        gearbox.connect()
        val reconnected = karoo.awaitValue {
            client.state.value.takeIf { it.link == PinionLink.CONNECTED && it.gear == 5 }
        }

        assertEquals(5, reconnected.gear)
        assertTrue(gearbox.connectionAttempts >= 2)
    }

    @Test
    fun `forgetting the gearbox clears the saved address`() {
        ble.grantPermissions()
        startAndConnect()
        assertEquals(gearbox.address, client.savedAddress)

        client.forget()

        assertNull(client.savedAddress)
    }
}
