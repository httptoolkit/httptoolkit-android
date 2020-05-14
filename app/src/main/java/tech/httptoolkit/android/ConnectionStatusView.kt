package tech.httptoolkit.android

import android.content.Context
import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.TextView

class ConnectionStatusView(
    context: Context,
    proxyConfig: ProxyConfig
) : LinearLayout(context) {

    init {
        LayoutInflater.from(context).inflate(
            when (whereIsCertTrusted(proxyConfig)) {
                "user" -> R.layout.connection_status_user
                "system" -> R.layout.connection_status_system
                else -> R.layout.connection_status_none
            },
        this, true)

        val connectedToText = findViewById<TextView>(R.id.connectedTo)
        connectedToText.text = context.getString(
            R.string.connected_details,
            proxyConfig.ip,
            proxyConfig.port
        )
    }

}