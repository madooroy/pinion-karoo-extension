package io.github.madooroy.pinionkaroo.extension

import fi.nikosavola.karooext.testing.robolectric.FakeKarooRule
import fi.nikosavola.karooext.testing.robolectric.ble.FakeBle
import fi.nikosavola.karooext.testing.robolectric.ble.FakeBlePeripheral
import io.github.madooroy.pinionkaroo.PinionBleClient
import io.github.madooroy.pinionkaroo.PinionProtocol
import io.github.madooroy.pinionkaroo.PinionProtocol.Parameter
import io.hammerhead.karooext.models.ActiveRideProfile
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.InRideAlert
import io.hammerhead.karooext.models.RideProfile
import io.hammerhead.karooext.models.StreamState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// The real service and BLE client talk to a simulated gearbox that answers read requests like a
// Pinion Smart.Shift does: a request is written, the reply comes back as an indication.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PinionExtensionServiceTest {
    @get:Rule val karoo = FakeKarooRule(requireReleasedRadios = true)

    private val ble = FakeBle(karoo.app)
    private lateinit var gearbox: FakeBlePeripheral

    private val pinionField = RideProfile.Page.Element(DataType.dataTypeId("pinion", "gear"), 1 to 1)

    private fun profile(vararg elements: RideProfile.Page.Element) =
        RideProfile("p", "Road", listOf(RideProfile.Page(false, elements.toList())), false, "cycling", "road")

    private fun reply(parameter: Parameter, value: Long): ByteArray =
        byteArrayOf(0x02, parameter.length.toByte()) + parameter.address +
            ByteArray(parameter.length) { (value shr (8 * it)).toByte() }

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
        val values = mapOf(
            Parameter.NUMBER_OF_GEARS to 12L,
            Parameter.CURRENT_GEAR to 5L,
            Parameter.BATTERY_LEVEL to 8750L,
            Parameter.SERIAL_NUMBER to 123456L,
        )
        gearbox.onWrite(PinionProtocol.REQUEST_UUID) { request ->
            val parameter = Parameter.entries.first { it.address.contentEquals(request.copyOfRange(2, 5)) }
            gearbox.notify(PinionProtocol.RESPONSE_UUID, reply(parameter, values.getValue(parameter)))
        }
    }

    // The client is a process-wide singleton, so each test starts from a fresh one.
    @After
    fun resetClient() {
        val field = PinionBleClient::class.java.getDeclaredField("instance").apply { isAccessible = true }
        (field.get(null) as PinionBleClient?)?.let { client ->
            val scope = PinionBleClient::class.java.getDeclaredField("scope").apply { isAccessible = true }
            (scope.get(client) as CoroutineScope).cancel()
        }
        field.set(null, null)
    }

    private fun connectedGearbox() {
        karoo.system.setActiveRideProfile(profile(pinionField))
        gearbox.advertise()
        gearbox.connect()
        gearbox.awaitSubscribed(PinionProtocol.CURRENT_GEAR_UUID)
    }

    private fun streamValue(state: StreamState, expected: Double) =
        state is StreamState.Streaming && state.dataPoint.singleValue == expected

    @Test
    fun `gear and battery from the gearbox become stream values`() {
        val host = karoo.host<PinionExtensionService>()
        val gear = host.startStream(PinionDataType.GEAR)
        val battery = host.startStream(PinionDataType.BATTERY)

        connectedGearbox()

        gear.await(10_000) { streamValue(it, 5.0) }
        battery.await(10_000) { streamValue(it, 87.5) }
        gearbox.notify(PinionProtocol.CURRENT_GEAR_UUID, byteArrayOf(7))
        gear.await(10_000) { streamValue(it, 7.0) }
    }

    @Test
    fun `an out of range gear notification is ignored`() {
        val host = karoo.host<PinionExtensionService>()
        val gear = host.startStream(PinionDataType.GEAR)
        connectedGearbox()
        gear.await(10_000) { streamValue(it, 5.0) }
        val mark = gear.mark()

        gearbox.notify(PinionProtocol.CURRENT_GEAR_UUID, byteArrayOf(13))
        gearbox.notify(PinionProtocol.CURRENT_GEAR_UUID, byteArrayOf(6))

        gear.await(10_000, after = mark) { streamValue(it, 6.0) }
        assertEquals(listOf(6.0), gear.items.drop(mark).mapNotNull { (it as? StreamState.Streaming)?.dataPoint?.singleValue })
    }

    @Test
    fun `a profile without a pinion field never scans`() {
        karoo.host<PinionExtensionService>()
        karoo.system.setActiveRideProfile(profile())
        karoo.awaitConsumer(ActiveRideProfile.Params)

        karoo.assertNoEffect<InRideAlert>(forMs = 500)

        assertFalse(ble.isScanning)
    }

    @Test
    fun `losing the link tells the rider to wake the gearbox`() {
        karoo.host<PinionExtensionService>()
        connectedGearbox()
        gearbox.awaitWrite(PinionProtocol.REQUEST_UUID)

        gearbox.disconnect()

        val alert = karoo.awaitEffect<InRideAlert> { it.id == "pinion-disconnected" }
        assertEquals(10_000L, alert.autoDismissMs)
    }
}
