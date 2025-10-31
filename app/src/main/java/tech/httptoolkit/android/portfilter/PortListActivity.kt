package tech.httptoolkit.android.portfilter

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import tech.httptoolkit.android.IntentExtras
import tech.httptoolkit.android.ui.HttpToolkitTheme

class PortListActivity : ComponentActivity() {

    private var currentPorts: Set<Int> = emptySet()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val initialPorts = intent.getIntArrayExtra(IntentExtras.SELECTED_PORTS_EXTRA)!!
            .toSet()

        currentPorts = initialPorts

        setContent {
            HttpToolkitTheme {
                PortListScreen(
                    initialPorts = initialPorts,
                    onPortsChanged = { newPorts ->
                        currentPorts = newPorts
                    },
                    defaultPorts = DEFAULT_PORTS
                )
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                setResult(RESULT_OK, Intent().putExtra(
                    IntentExtras.SELECTED_PORTS_EXTRA,
                    currentPorts.toIntArray()
                ))
                finish()
            }
        })
    }
}
