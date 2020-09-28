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

private val isLineageOs = Build.HOST.startsWith("lineage")

class ConnectionStatusView(
    context: Context,
    proxyConfig: ProxyConfig,
    totalAppCount: Int,
    interceptedAppCount: Int,
    changeApps: () -> Unit
) : LinearLayout(context) {

    init {
        val layout = when (whereIsCertTrusted(proxyConfig)) {
            "user" -> R.layout.connection_status_user
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
        connectedToText.text = context.getString(
            R.string.connected_details,
            proxyConfig.ip,
            proxyConfig.port
        )

        val appInterceptionStatus = findViewById<MaterialCardView>(R.id.appInterceptionStatus)
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
                            Uri.parse("https://gitlab.com/LineageOS/issues/android/-/issues/1706")
                        ))
                    }
                    .show()
            }
        }

        val appInterceptionStatusText = findViewById<TextView>(R.id.appInterceptionStatusText)
        appInterceptionStatusText.text = context.getString(
            when {
                totalAppCount == interceptedAppCount -> R.string.intercepting_all
                interceptedAppCount > 10 -> R.string.intercepting_selected
                else -> R.string.intercepting_few
            }
        , interceptedAppCount, if (interceptedAppCount != 1) "s" else "")
    }

}