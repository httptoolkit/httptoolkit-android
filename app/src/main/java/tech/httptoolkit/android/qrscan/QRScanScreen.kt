package tech.httptoolkit.android.qrscan

import android.util.Log
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.google.zxing.BarcodeFormat
import com.google.zxing.ResultPoint
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import com.journeyapps.barcodescanner.DefaultDecoderFactory

@Composable
fun QRScanScreen(
    onQRCodeScanned: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    AndroidView(
        factory = { context ->
            var lastScannedText: String? = null

            DecoratedBarcodeView(context).apply {
                barcodeView.decoderFactory = DefaultDecoderFactory(listOf(BarcodeFormat.QR_CODE))
                setStatusText("Scan HTTP Toolkit QR code to connect")

                val callback = object : BarcodeCallback {
                    override fun barcodeResult(result: BarcodeResult) {
                        val resultText = result.text
                        if (resultText == null || resultText == lastScannedText) {
                            // Prevent duplicate scans
                            return
                        }

                        lastScannedText = resultText
                        Log.i("QRScanScreen", "Scanned: $resultText")

                        if (resultText.startsWith("https://android.httptoolkit.tech/connect/")) {
                            onQRCodeScanned(resultText)
                        }
                    }

                    override fun possibleResultPoints(resultPoints: MutableList<ResultPoint?>?) {
                        // Not needed
                    }
                }

                decodeContinuous(callback)
                resume()
            }
        },
        modifier = modifier.fillMaxSize(),
        onRelease = { barcodeView ->
            barcodeView.pause()
        }
    )
}
