package io.github.madooroy.pinionkaroo

import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Opened from the Karoo app list. Its real job is to request the Bluetooth runtime permissions,
 * which the background extension service cannot do; it also shows live status for debugging.
 */
class MainActivity : Activity() {
    private val scope: CoroutineScope = MainScope()
    private var statusJob: Job? = null
    private lateinit var client: PinionBleClient
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        client = PinionBleClient.get(this)

        val padding = (16 * resources.displayMetrics.density).toInt()
        status = TextView(this).apply { textSize = 20f }
        val help = TextView(this).apply {
            setText(R.string.main_help)
            setPadding(0, padding, 0, padding)
        }
        val forget = Button(this).apply {
            setText(R.string.main_forget)
            setOnClickListener { client.forget() }
        }
        // The button goes above the help text and everything scrolls: on the Karoo 2's small screen
        // a button at the bottom ended up off screen.
        setContentView(
            ScrollView(this).apply {
                addView(
                    LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(padding, padding, padding, padding)
                        addView(status)
                        addView(forget)
                        addView(help)
                    },
                )
            },
        )

        val needed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PinionBleClient.REQUIRED_PERMISSIONS_S
        } else {
            PinionBleClient.REQUIRED_PERMISSIONS_LEGACY
        }
        if (needed.any { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }) {
            requestPermissions(needed, 0)
        }
    }

    override fun onStart() {
        super.onStart()
        client.start()
        statusJob = scope.launch {
            client.state.collect { state ->
                status.text = buildString {
                    appendLine("Status: ${state.link}")
                    appendLine("Gearbox: ${client.savedAddress ?: "none saved"}")
                    appendLine("Gear: ${state.gear ?: "--"} of ${state.gearCount}")
                    append("Battery: ${state.batteryPercent?.let { "%.1f%%".format(it) } ?: "--"}")
                }
            }
        }
    }

    override fun onStop() {
        statusJob?.cancel()
        client.stop()
        super.onStop()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
