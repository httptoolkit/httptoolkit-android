package tech.httptoolkit.android

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.text.method.LinkMovementMethod
import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.core.net.toUri

private val isLineageOs = Build.HOST.startsWith("lineage")

class ConnectionStatusView(
    context: Context,
    proxyConfig: ProxyConfig,
    totalAppCount: Int,
    interceptedAppCount: Int,
    changeApps: () -> Unit,
    interceptedPorts: Set<Int>,
    changePorts: () -> Unit
) : LinearLayout(context) {

    init {
        val layout = when (whereIsCertTrusted(proxyConfig)) {
            "user" ->
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N)
                    R.layout.connection_status_pre_v7
                else
                    R.layout.connection_status_user
            "system" -> R.layout.connection_status_system
            else -> R.layout.connection_status_none
        }
        LayoutInflater.from(context).inflate(layout, this, true)

        if (layout == R.layout.connection_status_user) {
            // Make inline links clickable:
            val statusText = findViewById<TextView>(R.id.connectionStatusText)
            if (statusText != null) statusText.movementMethod = LinkMovementMethod.getInstance()
        }

        val connectedToText = findViewById<TextView>(R.id.connectedTo)
        connectedToText.text = if (proxyConfig.ip == "127.0.0.1") {
            context.getString(R.string.connected_tunnel_details)
        } else {
            context.getString(
                R.string.connected_details,
                proxyConfig.ip,
                proxyConfig.port
            )
        }

        val appInterceptionStatus = findViewById<MaterialCardView>(R.id.interceptedAppsButton)
        appInterceptionStatus.setOnClickListener { _ ->
            if (!isLineageOs) {
                changeApps()
            } else {
                MaterialAlertDialogBuilder(context)
                    .setTitle("Not available")
                    .setIcon(R.drawable.ic_exclamation_triangle)
                    .setMessage(
                        """
                        Per-app filtering is not possible on LineageOS, due to a bug in Lineage's VPN implementation.

                        If you'd like this fixed, please upvote the bug in their issue tracker.
                        """.trimIndent()
                    )
                    .setNegativeButton("Cancel") { _, _ -> }
                    .setPositiveButton("View the bug") { _, _ ->
                        context.startActivity(Intent(
                            Intent.ACTION_VIEW,
                            "https://gitlab.com/LineageOS/issues/android/-/issues/1706".toUri()
                        ))
                    }
                    .show()
            }
        }

        val appInterceptionStatusText = findViewById<TextView>(R.id.interceptedAppsStatus)
        appInterceptionStatusText.text = context.getString(
            when {
                totalAppCount == interceptedAppCount -> R.string.all_apps
                interceptedAppCount > 10 -> R.string.selected_apps
                else -> R.string.few_apps
            },
            interceptedAppCount,
            if (interceptedAppCount != 1) "s" else ""
        )

        val portInterceptionStatus = findViewById<MaterialCardView>(R.id.interceptedPortsButton)
        portInterceptionStatus.setOnClickListener { _ -> changePorts() }

        val portInterceptionStatusText = findViewById<TextView>(R.id.interceptedPortsStatus)
        portInterceptionStatusText.text = context.getString(
            when {
                (interceptedPorts == DEFAULT_PORTS) -> R.string.default_ports
                interceptedPorts.size > 10 -> R.string.selected_ports
                else -> R.string.few_ports
            },
            interceptedPorts.size,
            if (interceptedPorts.size != 1) "s" else ""
        )
    }

}
