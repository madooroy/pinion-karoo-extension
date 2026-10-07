package io.github.madooroy.pinionkaroo

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.os.SystemClock
import io.github.madooroy.pinionkaroo.PinionProtocol.Parameter
import io.github.madooroy.pinionkaroo.PinionProtocol.toHex
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

enum class PinionLink {
    /** Nobody has asked for data yet */
    IDLE,
    NO_PERMISSION,
    BLUETOOTH_OFF,

    /** Scanning: waiting for the rider to hold the rear shift button for 3 seconds */
    SEARCHING,
    CONNECTING,
    CONNECTED,
}

data class PinionState(
    val link: PinionLink = PinionLink.IDLE,
    val gear: Int? = null,
    val gearCount: Int = PinionProtocol.MAX_GEARS,
    val batteryPercent: Double? = null,
)

/**
 * Finds the gearbox, keeps a connection to it and publishes gear and battery as [state].
 *
 * The gearbox only advertises after its rear shift button is held for 3 seconds, and it stops
 * advertising again when a connection drops. So "reconnecting" can only mean scanning until the
 * rider wakes it up; this client scans for as long as it is started.
 */
@SuppressLint("MissingPermission") // checked in hasPermissions() before any Bluetooth call
class PinionBleClient private constructor(private val context: Context) {
    private val prefs = context.getSharedPreferences("pinion", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var users = 0
    private var lingerJob: Job? = null

    private val _state = MutableStateFlow(PinionState())
    val state: StateFlow<PinionState> = _state.asStateFlow()

    /** Address of the gearbox we are locked to, or null if the next gearbox seen will be adopted. */
    val savedAddress: String?
        get() = prefs.getString(KEY_ADDRESS, null)

    /**
     * Reference counted: the connection runs while at least one caller has started it. The callers are
     * the extension service (while the active ride profile has a Pinion field) and the status activity.
     */
    @Synchronized
    fun start() {
        users++
        lingerJob?.cancel()
        lingerJob = null
        if (job?.isActive != true) {
            Timber.i("Starting BLE client")
            job = scope.launch { runForever() }
        }
    }

    /**
     * Shuts down only after [STOP_LINGER_MS] without users. Passing through another profile on the
     * launcher must not drop the link: getting it back costs the rider a 3 second button hold.
     */
    @Synchronized
    fun stop() {
        users = (users - 1).coerceAtLeast(0)
        if (users == 0 && job != null) {
            Timber.i("No users left, stopping BLE client in ${STOP_LINGER_MS / 1000} s")
            lingerJob?.cancel()
            lingerJob = scope.launch {
                delay(STOP_LINGER_MS)
                shutDown()
            }
        }
    }

    @Synchronized
    private fun shutDown() {
        if (users > 0) return
        Timber.i("Stopping BLE client")
        job?.cancel()
        job = null
        _state.value = PinionState()
    }

    /** Drop the saved gearbox so the next one put into pairing mode is used. */
    @Synchronized
    fun forget() {
        Timber.i("Forgetting gearbox $savedAddress")
        prefs.edit().remove(KEY_ADDRESS).apply()
        if (job != null) {
            job?.cancel()
            _state.value = PinionState()
            job = scope.launch { runForever() }
        }
    }

    private suspend fun runForever() {
        while (true) {
            val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            if (!hasPermissions()) {
                setLink(PinionLink.NO_PERMISSION)
                delay(PRECONDITION_POLL_MS)
                continue
            }
            if (adapter == null || !adapter.isEnabled) {
                setLink(PinionLink.BLUETOOTH_OFF)
                delay(PRECONDITION_POLL_MS)
                continue
            }
            try {
                setLink(PinionLink.SEARCHING)
                val device = scan(adapter)
                setLink(PinionLink.CONNECTING)
                runSession(device)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w("Connection ended: ${e.message}")
            }
            // Keep the battery level, but the gear is unknown from here on
            _state.update { it.copy(link = PinionLink.SEARCHING, gear = null) }
            delay(RETRY_DELAY_MS)
        }
    }

    private fun setLink(link: PinionLink) {
        if (_state.value.link != link) Timber.i("Link: ${_state.value.link} -> $link")
        _state.update { it.copy(link = link) }
    }

    private fun hasPermissions(): Boolean {
        val needed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            REQUIRED_PERMISSIONS_S
        } else {
            REQUIRED_PERMISSIONS_LEGACY
        }
        return needed.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    }

    // region Scanning

    /** Suspends until the gearbox is seen advertising. */
    private suspend fun scan(adapter: BluetoothAdapter): BluetoothDevice {
        val address = savedAddress
        // Once we know our gearbox only it will do, so another Pinion bike nearby is not picked up
        val filter = if (address != null) {
            ScanFilter.Builder().setDeviceAddress(address).build()
        } else {
            ScanFilter.Builder().setServiceUuid(ParcelUuid(PinionProtocol.SERVICE_UUID)).build()
        }
        Timber.i(if (address != null) "Scanning for saved gearbox $address" else "Scanning for any Pinion gearbox")
        while (true) {
            // Android silently demotes scans that run longer than 30 minutes, so restart periodically
            val device = withTimeoutOrNull(SCAN_RESTART_MS) { scanOnce(adapter, filter) }
            if (device != null) return device
            Timber.d("Still scanning...")
        }
    }

    private suspend fun scanOnce(adapter: BluetoothAdapter, filter: ScanFilter): BluetoothDevice =
        suspendCancellableCoroutine { cont ->
            val scanner = adapter.bluetoothLeScanner
            if (scanner == null) {
                cont.resumeWithException(IOException("Bluetooth turned off"))
                return@suspendCancellableCoroutine
            }
            val done = AtomicBoolean(false)
            val callback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    if (done.compareAndSet(false, true)) {
                        Timber.i("Found ${result.device.address} rssi=${result.rssi} name=${result.scanRecord?.deviceName}")
                        runCatching { scanner.stopScan(this) }
                        cont.resume(result.device)
                    }
                }

                override fun onScanFailed(errorCode: Int) {
                    if (done.compareAndSet(false, true)) {
                        cont.resumeWithException(IOException("Scan failed, error $errorCode"))
                    }
                }
            }
            // The advertising window after the button hold is short, so scan continuously
            val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
            scanner.startScan(listOf(filter), settings, callback)
            cont.invokeOnCancellation { runCatching { scanner.stopScan(callback) } }
        }

    // endregion

    // region GATT session

    private sealed interface GattEvent {
        data class ConnectionState(val status: Int, val newState: Int) : GattEvent
        data class ServicesDiscovered(val status: Int) : GattEvent
        data class DescriptorWritten(val status: Int) : GattEvent
        data class CharacteristicWritten(val status: Int) : GattEvent
        class Changed(val uuid: UUID, val value: ByteArray) : GattEvent
    }

    /** Connects and services the connection. Only ever returns by throwing. */
    private suspend fun runSession(device: BluetoothDevice) {
        val events = Channel<GattEvent>(Channel.UNLIMITED)
        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                Timber.d("onConnectionStateChange status=$status newState=$newState")
                events.trySend(GattEvent.ConnectionState(status, newState))
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                events.trySend(GattEvent.ServicesDiscovered(status))
            }

            override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
                events.trySend(GattEvent.DescriptorWritten(status))
            }

            override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
                events.trySend(GattEvent.CharacteristicWritten(status))
            }

            // The pre-API 33 callback: Karoo 2 is API 26, and newer versions still deliver here by default
            @Deprecated("Deprecated in Java")
            @Suppress("DEPRECATION")
            override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                val value = characteristic.value?.copyOf() ?: return
                events.trySend(GattEvent.Changed(characteristic.uuid, value))
            }
        }

        Timber.i("Connecting to ${device.address}")
        val gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            ?: throw IOException("connectGatt returned null")
        try {
            Session(gatt, events).run()
        } finally {
            // Always close: a leaked BluetoothGatt is what causes the infamous status 133 on later attempts
            runCatching { gatt.disconnect() }
            runCatching { gatt.close() }
            Timber.i("GATT closed")
        }
    }

    private inner class Session(private val gatt: BluetoothGatt, private val events: Channel<GattEvent>) {
        private lateinit var request: BluetoothGattCharacteristic

        suspend fun run() {
            await("connection", CONNECT_TIMEOUT_MS) {
                (it as? GattEvent.ConnectionState)?.takeIf { e ->
                    e.status == BluetoothGatt.GATT_SUCCESS && e.newState == BluetoothProfile.STATE_CONNECTED
                }
            }

            if (!gatt.discoverServices()) throw IOException("discoverServices refused")
            val discovered = await("service discovery", GATT_TIMEOUT_MS) { it as? GattEvent.ServicesDiscovered }
            if (discovered.status != BluetoothGatt.GATT_SUCCESS) throw IOException("Service discovery failed, status ${discovered.status}")

            val service = gatt.getService(PinionProtocol.SERVICE_UUID)
                ?: throw IOException("Pinion service missing; found ${gatt.services.map { it.uuid }}")
            val gear = service.getCharacteristic(PinionProtocol.CURRENT_GEAR_UUID) ?: throw IOException("Gear characteristic missing")
            val response = service.getCharacteristic(PinionProtocol.RESPONSE_UUID) ?: throw IOException("Response characteristic missing")
            request = service.getCharacteristic(PinionProtocol.REQUEST_UUID) ?: throw IOException("Request characteristic missing")

            // It really is a Pinion gearbox: lock on to it for future rides
            if (savedAddress != gatt.device.address) {
                Timber.i("Saving gearbox ${gatt.device.address}")
                prefs.edit().putString(KEY_ADDRESS, gatt.device.address).apply()
            }

            subscribe(response, BluetoothGattDescriptor.ENABLE_INDICATION_VALUE)
            subscribe(gear, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            setLink(PinionLink.CONNECTED)

            // The gear is only notified when it changes, so read the starting point
            read(Parameter.NUMBER_OF_GEARS)?.let { count ->
                if (count in 1..PinionProtocol.MAX_GEARS) _state.update { it.copy(gearCount = count.toInt()) }
            }
            read(Parameter.CURRENT_GEAR)?.let { onGear(byteArrayOf(it.toByte())) }
            readBattery()

            var nextBatteryRead = SystemClock.elapsedRealtime() + BATTERY_POLL_MS
            while (true) {
                val wait = nextBatteryRead - SystemClock.elapsedRealtime()
                val event = if (wait > 0) withTimeoutOrNull(wait) { events.receive() } else null
                if (event != null) {
                    handleUnsolicited(event)
                } else {
                    readBattery()
                    nextBatteryRead = SystemClock.elapsedRealtime() + BATTERY_POLL_MS
                }
            }
        }

        private fun onGear(value: ByteArray) {
            val gear = PinionProtocol.parseGear(value, _state.value.gearCount)
            if (gear == null) {
                Timber.w("Ignoring out of range gear payload [${value.toHex()}]")
                return
            }
            Timber.i("Gear $gear")
            _state.update { it.copy(gear = gear) }
        }

        private suspend fun readBattery() {
            read(Parameter.BATTERY_LEVEL)?.let { raw ->
                val percent = PinionProtocol.batteryPercent(raw)
                Timber.i("Battery $percent %")
                _state.update { it.copy(batteryPercent = percent) }
            }
        }

        /** Events that can arrive at any time. Throws when the link is gone. */
        private fun handleUnsolicited(event: GattEvent) {
            when (event) {
                is GattEvent.ConnectionState ->
                    if (event.newState == BluetoothProfile.STATE_DISCONNECTED) {
                        throw IOException("Disconnected, status ${event.status}")
                    }
                is GattEvent.Changed ->
                    if (event.uuid == PinionProtocol.CURRENT_GEAR_UUID) {
                        onGear(event.value)
                    } else {
                        Timber.d("Unexpected data on ${event.uuid}: [${event.value.toHex()}]")
                    }
                else -> Timber.d("Unexpected $event")
            }
        }

        /** Waits for the event [match] accepts, servicing gear notifications and disconnects meanwhile. */
        private suspend fun <T : Any> await(what: String, timeoutMs: Long, match: (GattEvent) -> T?): T =
            withTimeoutOrNull(timeoutMs) {
                var result: T? = null
                while (result == null) {
                    val event = events.receive()
                    result = match(event)
                    if (result == null) handleUnsolicited(event)
                }
                result
            } ?: throw IOException("Timed out waiting for $what")

        @Suppress("DEPRECATION") // the non-deprecated variants are API 33+
        private suspend fun subscribe(characteristic: BluetoothGattCharacteristic, cccdValue: ByteArray) {
            if (!gatt.setCharacteristicNotification(characteristic, true)) throw IOException("setCharacteristicNotification refused")
            val cccd = characteristic.getDescriptor(PinionProtocol.CCCD_UUID) ?: throw IOException("No CCCD on ${characteristic.uuid}")
            cccd.value = cccdValue
            if (!gatt.writeDescriptor(cccd)) throw IOException("writeDescriptor refused")
            val written = await("subscription to ${characteristic.uuid}", GATT_TIMEOUT_MS) { it as? GattEvent.DescriptorWritten }
            // Status 5 / 8 / 15 here would mean the gearbox wants a bonded, encrypted link
            if (written.status != BluetoothGatt.GATT_SUCCESS) throw IOException("Subscribing to ${characteristic.uuid} failed, status ${written.status}")
        }

        /** One request/response exchange. Returns null if the gearbox refuses the parameter. */
        @Suppress("DEPRECATION")
        private suspend fun read(parameter: Parameter): Long? {
            val payload = PinionProtocol.readRequest(parameter)
            request.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            request.value = payload
            if (!gatt.writeCharacteristic(request)) throw IOException("writeCharacteristic refused")
            val written = await("write of $parameter", GATT_TIMEOUT_MS) { it as? GattEvent.CharacteristicWritten }
            if (written.status != BluetoothGatt.GATT_SUCCESS) throw IOException("Request for $parameter failed, status ${written.status}")

            val reply = await("reply to $parameter", GATT_TIMEOUT_MS) {
                (it as? GattEvent.Changed)?.takeIf { e -> e.uuid == PinionProtocol.RESPONSE_UUID }
            }.value
            val value = PinionProtocol.parseReadReply(parameter, reply)
            Timber.d("$parameter: tx [${payload.toHex()}] rx [${reply.toHex()}] -> $value")
            if (value == null) Timber.w("Bad reply to $parameter: [${reply.toHex()}]")
            return value
        }
    }

    // endregion

    companion object {
        private const val KEY_ADDRESS = "gearbox_address"

        private const val PRECONDITION_POLL_MS = 5_000L
        private const val RETRY_DELAY_MS = 1_000L
        private const val STOP_LINGER_MS = 120_000L
        private const val SCAN_RESTART_MS = 10 * 60_000L
        private const val CONNECT_TIMEOUT_MS = 10_000L
        private const val GATT_TIMEOUT_MS = 5_000L
        private const val BATTERY_POLL_MS = 60_000L

        val REQUIRED_PERMISSIONS_S = arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        val REQUIRED_PERMISSIONS_LEGACY = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

        @Volatile
        private var instance: PinionBleClient? = null

        /** One client per process, shared by the extension service and the activity. */
        fun get(context: Context): PinionBleClient =
            instance ?: synchronized(this) {
                instance ?: PinionBleClient(context.applicationContext).also { instance = it }
            }
    }
}
