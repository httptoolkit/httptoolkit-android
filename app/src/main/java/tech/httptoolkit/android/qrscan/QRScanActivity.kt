package tech.httptoolkit.android.qrscan

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import tech.httptoolkit.android.IntentExtras
import tech.httptoolkit.android.ui.HttpToolkitTheme

class QRScanActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            HttpToolkitTheme {
                QRScanScreen(
                    onQRCodeScanned = { scannedUrl ->
                        setResult(RESULT_OK, Intent().putExtra(
                            IntentExtras.SCANNED_URL_EXTRA,
                            scannedUrl
                        ))
                        finish()
                    }
                )
            }
        }
    }
}
