package io.github.madooroy.pinionkaroo.extension

import fi.nikosavola.karooext.testing.robolectric.FakeKarooRule
import fi.nikosavola.karooext.testing.robolectric.ble.FakeBle
import fi.nikosavola.karooext.testing.robolectric.ble.FakeBlePeripheral
import io.github.madooroy.pinionkaroo.PinionClientSingleton
import io.github.madooroy.pinionkaroo.PinionProtocol
import io.github.madooroy.pinionkaroo.PinionProtocol.Parameter
import io.github.madooroy.pinionkaroo.R
import io.hammerhead.karooext.models.ActiveRideProfile
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.InRideAlert
import io.hammerhead.karooext.models.RideProfile
import io.hammerhead.karooext.models.StreamState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Service-level behaviours the first suite does not cover: stream states before and during a ride,
// the gear limit read from the gearbox, error replies, the link surviving a profile switch, radio
// release and consumer cleanup.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PinionExtensionBehaviourTest {
    @get:Rule val karoo = FakeKarooRule(requireReleasedRadios = true)

    private val ble = FakeBle(karoo.app)
    private lateinit var gearbox: FakeBlePeripheral

    private val pinionField = RideProfile.Page.Element(DataType.dataTypeId("pinion", "gear"), 1 to 1)

    // Mutable so a test can change what the gearbox reports before it is read.
    private val values = mutableMapOf(
        Parameter.NUMBER_OF_GEARS to 12L,
        Parameter.CURRENT_GEAR to 5L,
        Parameter.BATTERY_LEVEL to 8750L,
        Parameter.SERIAL_NUMBER to 123456L,
    )
    private var respond: (Parameter) -> ByteArray = { reply(it, values.getValue(it)) }

    private fun profile(vararg elements: RideProfile.Page.Element) =
        RideProfile("p", "Road", listOf(RideProfile.Page(false, elements.toList())), false, "cycling", "road")

    private fun reply(parameter: Parameter, value: Long): ByteArray =
        byteArrayOf(0x02, parameter.length.toByte()) + parameter.address +
            ByteArray(parameter.length) { (value shr (8 * it)).toByte() }

    private fun errorReply(parameter: Parameter): ByteArray =
        byteArrayOf(0x00, parameter.length.toByte()) + parameter.address

    private fun replyParameter(request: ByteArray): Parameter =
        Parameter.entries.first { it.address.contentEquals(request.copyOfRange(2, 5)) }

    @Before
    fun setUp() {
        ble.grantPermissions()
        gearbox = ble.peripheral("AA:BB:CC:DD:EE:01", name = "Pinion") {
            service(PinionProtocol.SERVICE_UUID, advertised = true) {
                characteristic(PinionProtocol.CURRENT_GEAR_UUID, notify = true)
                characteristic(PinionProtocol.REQUEST_UUID, write = true)
                characteristic(PinionProtocol.RESPONSE_UUID, indicate = true)
            }
        }
        gearbox.onWrite(PinionProtocol.REQUEST_UUID) { request ->
            gearbox.notify(PinionProtocol.RESPONSE_UUID, respond(replyParameter(request)))
        }
    }

    @After
    fun resetClient() = PinionClientSingleton.reset()

    private fun connectedGearbox() {
        karoo.system.setActiveRideProfile(profile(pinionField))
        gearbox.advertise()
        gearbox.connect()
        gearbox.awaitSubscribed(PinionProtocol.CURRENT_GEAR_UUID)
    }

    private fun streamValue(state: StreamState, expected: Double) =
        state is StreamState.Streaming && state.dataPoint.singleValue == expected

    @Test
    fun `before a pinion profile is active the fields are not available`() {
        val host = karoo.host<PinionExtensionService>()
        val gear = host.startStream(PinionDataType.GEAR)

        gear.await(10_000) { it is StreamState.NotAvailable }

        assertFalse(ble.isScanning)
    }

    @Test
    fun `activating a profile with a pinion field starts scanning and the field searches`() {
        val host = karoo.host<PinionExtensionService>()
        val gear = host.startStream(PinionDataType.GEAR)

        karoo.system.setActiveRideProfile(profile(pinionField))
        karoo.awaitConsumer(ActiveRideProfile.Params)
        ble.awaitScan()

        gear.await(10_000) { it is StreamState.Searching }
        assertTrue(ble.isScanning)
    }

    @Test
    fun `the gear count read from the gearbox limits which gears are shown`() {
        values[Parameter.NUMBER_OF_GEARS] = 9L
        val host = karoo.host<PinionExtensionService>()
        val gear = host.startStream(PinionDataType.GEAR)

        connectedGearbox()
        gear.await(10_000) { streamValue(it, 5.0) }
        val mark = gear.mark()

        gearbox.notify(PinionProtocol.CURRENT_GEAR_UUID, byteArrayOf(10)) // above the gearbox's 9
        gearbox.notify(PinionProtocol.CURRENT_GEAR_UUID, byteArrayOf(9))

        gear.await(10_000, after = mark) { streamValue(it, 9.0) }
        assertEquals(
            listOf(9.0),
            gear.items.drop(mark).mapNotNull { (it as? StreamState.Streaming)?.dataPoint?.singleValue },
        )
    }

    @Test
    fun `battery above full is clamped to 100 percent`() {
        values[Parameter.BATTERY_LEVEL] = 15000L
        val host = karoo.host<PinionExtensionService>()
        val battery = host.startStream(PinionDataType.BATTERY)

        connectedGearbox()

        battery.await(10_000) { streamValue(it, 100.0) }
    }

    @Test
    fun `an error reply leaves the battery field searching while gear still streams`() {
        respond = { parameter ->
            if (parameter == Parameter.BATTERY_LEVEL) errorReply(parameter) else reply(parameter, values.getValue(parameter))
        }
        val host = karoo.host<PinionExtensionService>()
        val gear = host.startStream(PinionDataType.GEAR)
        val battery = host.startStream(PinionDataType.BATTERY)

        connectedGearbox()
        gear.await(10_000) { streamValue(it, 5.0) }
        // A later gear notification proves the initial battery read already happened and failed.
        gearbox.notify(PinionProtocol.CURRENT_GEAR_UUID, byteArrayOf(7))
        gear.await(10_000) { streamValue(it, 7.0) }

        assertFalse(battery.items.any { it is StreamState.Streaming })
    }

    @Test
    fun `the disconnect alert tells the rider to hold the button`() {
        karoo.host<PinionExtensionService>()
        connectedGearbox()
        gearbox.awaitWrite(PinionProtocol.REQUEST_UUID)

        gearbox.disconnect()

        val alert = karoo.awaitEffect<InRideAlert> { it.id == "pinion-disconnected" }
        assertEquals(karoo.app.getString(R.string.alert_disconnected_title), alert.title)
        assertEquals(karoo.app.getString(R.string.alert_disconnected_detail), alert.detail)
    }

    @Test
    fun `the disconnect alert is raised only once per disconnection`() {
        karoo.host<PinionExtensionService>()
        connectedGearbox()
        gearbox.awaitWrite(PinionProtocol.REQUEST_UUID)

        gearbox.disconnect()
        karoo.awaitEffect<InRideAlert> { it.id == "pinion-disconnected" }
        val mark = karoo.system.effects.size

        karoo.assertNoEffect<InRideAlert>(forMs = 500, after = mark) { it.id == "pinion-disconnected" }
    }

    @Test
    fun `no alert is raised while the gearbox is merely being searched for`() {
        karoo.host<PinionExtensionService>()
        karoo.system.setActiveRideProfile(profile(pinionField))
        ble.awaitScan()

        karoo.assertNoEffect<InRideAlert>(forMs = 500)
    }

    @Test
    fun `switching to a profile without a pinion field keeps the gearbox link`() {
        val host = karoo.host<PinionExtensionService>()
        val gear = host.startStream(PinionDataType.GEAR)
        connectedGearbox()
        gear.await(10_000) { streamValue(it, 5.0) }

        karoo.system.setActiveRideProfile(profile())
        assertTrue("the linger should have kept the link", gearbox.isConnected)

        karoo.system.setActiveRideProfile(profile(pinionField))
        gearbox.notify(PinionProtocol.CURRENT_GEAR_UUID, byteArrayOf(7))
        gear.await(10_000) { streamValue(it, 7.0) }
    }

    @Test
    fun `destroying the service releases the bluetooth radio`() {
        karoo.host<PinionExtensionService>()
        karoo.system.setActiveRideProfile(profile(pinionField))
        ble.awaitScan()
        karoo.awaitValue { true.takeIf { karoo.system.bluetoothClaims.isNotEmpty() } }

        karoo.close()

        assertTrue(karoo.system.bluetoothClaims.isEmpty())
    }

}
