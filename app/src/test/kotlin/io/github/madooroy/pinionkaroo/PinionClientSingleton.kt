package io.github.madooroy.pinionkaroo

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel

/**
 * PinionBleClient is a process-wide singleton, so a test that starts one leaves it running for the
 * next test. Cancel its scope and clear the instance so each test starts from a fresh client.
 */
object PinionClientSingleton {
    fun reset() {
        val field = PinionBleClient::class.java.getDeclaredField("instance").apply { isAccessible = true }
        (field.get(null) as PinionBleClient?)?.let { client ->
            val scope = PinionBleClient::class.java.getDeclaredField("scope").apply { isAccessible = true }
            (scope.get(client) as CoroutineScope).cancel()
        }
        field.set(null, null)
    }
}
