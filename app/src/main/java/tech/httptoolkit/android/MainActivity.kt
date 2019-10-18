package tech.httptoolkit.android

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast

class MainActivity : AppCompatActivity() {

    private var vpnEnabled: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
    }

    fun toggleVPN(view: View) {
        this.vpnEnabled = !this.vpnEnabled
        val toggleButton = findViewById<TextView>(R.id.toggleButton)
        toggleButton.text = if (this.vpnEnabled) "Stop Intercepting" else "Start Intercepting"
    }

}
