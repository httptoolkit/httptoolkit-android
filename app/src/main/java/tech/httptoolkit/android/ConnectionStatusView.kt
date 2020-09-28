package tech.httptoolkit.android

import android.content.Context
import android.text.method.LinkMovementMethod
import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.card.MaterialCardView

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
        appInterceptionStatus.setOnClickListener { _ -> changeApps() }

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