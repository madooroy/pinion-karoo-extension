package io.github.madooroy.pinionkaroo.extension

import io.github.madooroy.pinionkaroo.PinionBleClient
import io.github.madooroy.pinionkaroo.PinionLink
import io.github.madooroy.pinionkaroo.R
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.models.ActiveRideProfile
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.InRideAlert
import io.hammerhead.karooext.models.ReleaseBluetooth
import io.hammerhead.karooext.models.RequestBluetooth
import io.hammerhead.karooext.models.RideProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Entry point bound by the Karoo system (see AndroidManifest.xml and res/xml/extension_info.xml).
 * Karoo keeps this service alive while the extension is installed. The gearbox connection is not
 * tied to it: the BLE client only runs while the active ride profile has a Pinion field on one of
 * its pages, so riding another bike with another profile does not scan.
 * (Field streams cannot be used for this: Karoo keeps them running whatever profile is open.)
 */
class PinionExtensionService : KarooExtension(EXTENSION_ID, "0.1.0") {
    private lateinit var karooSystem: KarooSystemService
    private lateinit var client: PinionBleClient
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var holdsClient = false

    override val types by lazy {
        listOf(
            // Limits: only a gear within 1..gearCount (at most 12) is ever shown
            PinionDataType(client, extension, PinionDataType.GEAR) { state ->
                state.gear?.takeIf { it in 1..state.gearCount }?.toDouble()
            },
            PinionDataType(client, extension, PinionDataType.BATTERY) { state -> state.batteryPercent },
        )
    }

    override fun onCreate() {
        super.onCreate()
        Timber.i("Extension service created")
        client = PinionBleClient.get(this)
        karooSystem = KarooSystemService(this)
        karooSystem.connect { connected ->
            Timber.i("Karoo system connected=$connected")
            if (connected) {
                // Ask Karoo to keep the Bluetooth radio on for us
                karooSystem.dispatch(RequestBluetooth(extension))
            }
        }
        karooSystem.addConsumer<ActiveRideProfile> { event -> onActiveProfile(event.profile) }
        scope.launch { alertOnDisconnect() }
    }

    @Synchronized
    private fun onActiveProfile(profile: RideProfile) {
        val prefix = DataType.dataTypeId(extension, "")
        val wanted = profile.pages.any { page -> page.elements.any { it.dataTypeId.startsWith(prefix) } }
        Timber.i("Active profile \"${profile.name}\" has Pinion fields: $wanted")
        if (wanted && !holdsClient) client.start()
        if (!wanted && holdsClient) client.stop()
        holdsClient = wanted
    }

    override fun onDestroy() {
        Timber.i("Extension service destroyed")
        scope.cancel()
        synchronized(this) {
            if (holdsClient) client.stop()
            holdsClient = false
        }
        karooSystem.dispatch(ReleaseBluetooth(extension))
        karooSystem.disconnect()
        super.onDestroy()
    }

    /**
     * The gearbox stops advertising when the link drops, so it cannot come back by itself:
     * tell the rider what to do.
     */
    private suspend fun alertOnDisconnect() {
        var wasConnected = false
        client.state.map { it.link }.distinctUntilChanged().collect { link ->
            if (wasConnected && link != PinionLink.CONNECTED && link != PinionLink.IDLE) {
                karooSystem.dispatch(
                    InRideAlert(
                        id = "pinion-disconnected",
                        icon = R.drawable.ic_pinion,
                        title = getString(R.string.alert_disconnected_title),
                        detail = getString(R.string.alert_disconnected_detail),
                        autoDismissMs = 10_000,
                        backgroundColor = R.color.alert_background,
                        textColor = R.color.alert_text,
                    ),
                )
            }
            wasConnected = link == PinionLink.CONNECTED
        }
    }

    companion object {
        /** Must match `id` in res/xml/extension_info.xml */
        const val EXTENSION_ID = "pinion"
    }
}
