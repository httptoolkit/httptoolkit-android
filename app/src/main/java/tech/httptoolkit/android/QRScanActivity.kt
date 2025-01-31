package tech.httptoolkit.android

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import com.google.zxing.BarcodeFormat
import com.google.zxing.ResultPoint
import com.google.zxing.client.android.BeepManager
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import com.journeyapps.barcodescanner.DefaultDecoderFactory

class QRScanActivity : Activity() {
    private var barcodeView: DecoratedBarcodeView? = null
    private var beepManager: BeepManager? = null
    private var lastText: String? = null

    private val callback: BarcodeCallback = object : BarcodeCallback {
        override fun barcodeResult(result: BarcodeResult) {
            val resultText = result.text
            if (resultText == null || resultText == lastText) {
                // Prevent duplicate scans
                return
            }

            lastText = resultText
            Log.i("QRScanActivity", "Scanned: $resultText")

            if (lastText!!.startsWith("https://android.httptoolkit.tech/connect/")) {
                beepManager!!.playBeepSoundAndVibrate()
                setResult(RESULT_OK, Intent().putExtra(IntentExtras.SCANNED_URL_EXTRA, lastText))
                finish()
            }
        }

        override fun possibleResultPoints(resultPoints: MutableList<ResultPoint?>?) {
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.qr_scan_activity)
        barcodeView = findViewById<DecoratedBarcodeView>(R.id.barcode_scanner)
        barcodeView!!.barcodeView.decoderFactory = DefaultDecoderFactory(listOf(BarcodeFormat.QR_CODE))
        barcodeView!!.initializeFromIntent(intent)
        barcodeView!!.decodeContinuous(callback)
        barcodeView!!.setStatusText("Scan HTTPToolkit QR code to connect")
        beepManager = BeepManager(this)
    }

    override fun onResume() {
        super.onResume()
        barcodeView!!.resume()
    }

    override fun onPause() {
        super.onPause()
        barcodeView!!.pause()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return barcodeView!!.onKeyDown(keyCode, event) || super.onKeyDown(keyCode, event)
    }
}