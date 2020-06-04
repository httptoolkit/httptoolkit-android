package tech.httptoolkit.android

import android.content.Context
import android.text.method.LinkMovementMethod
import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.TextView

class ConnectionStatusView(
    context: Context,
    proxyConfig: ProxyConfig
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
    }

}