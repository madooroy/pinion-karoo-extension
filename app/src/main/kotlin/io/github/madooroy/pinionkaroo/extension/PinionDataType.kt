package io.github.madooroy.pinionkaroo.extension

import io.github.madooroy.pinionkaroo.PinionBleClient
import io.github.madooroy.pinionkaroo.PinionLink
import io.github.madooroy.pinionkaroo.PinionState
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.StreamState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * A standard numeric Karoo field fed from the gearbox state. The extension only streams the value;
 * Karoo draws the title and the number itself (`graphical="false"` in extension_info.xml), which is
 * what makes these fields look exactly like the built-in ones. While the gearbox is not connected
 * Karoo shows its own "Searching..." text.
 *
 * A custom RemoteViews field was tried first and dropped: Karoo's font is not available to
 * extensions, so it could only ever be an imitation (see CLAUDE.md).
 */
class PinionDataType(
    private val client: PinionBleClient,
    extension: String,
    typeId: String,
    private val value: (PinionState) -> Double?,
) : DataTypeImpl(extension, typeId) {

    override fun startStream(emitter: Emitter<StreamState>) {
        Timber.d("Start $typeId stream")
        val job = CoroutineScope(Dispatchers.IO).launch {
            client.state
                .map { it.toStreamState() }
                .distinctUntilChanged()
                .collect { emitter.onNext(it) }
        }
        emitter.setCancellable {
            Timber.d("Stop $typeId stream")
            job.cancel()
        }
    }

    private fun PinionState.toStreamState(): StreamState {
        val current = value(this)
        return when (link) {
            PinionLink.CONNECTED ->
                if (current != null) {
                    StreamState.Streaming(DataPoint(dataTypeId, mapOf(DataType.Field.SINGLE to current)))
                } else {
                    StreamState.Searching
                }
            PinionLink.SEARCHING, PinionLink.CONNECTING -> StreamState.Searching
            PinionLink.IDLE, PinionLink.BLUETOOTH_OFF, PinionLink.NO_PERMISSION -> StreamState.NotAvailable
        }
    }

    companion object {
        /** Must match the `typeId`s in res/xml/extension_info.xml */
        const val GEAR = "gear"
        const val BATTERY = "battery"
    }
}
