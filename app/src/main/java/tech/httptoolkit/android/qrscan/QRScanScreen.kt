package tech.httptoolkit.android.qrscan

import android.util.Log
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.google.zxing.BarcodeFormat
import com.google.zxing.ResultPoint
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import com.journeyapps.barcodescanner.DefaultDecoderFactory
import tech.httptoolkit.android.Constants
import tech.httptoolkit.android.R

private const val TAG = "QRScanScreen"

@Composable
fun QRScanScreen(
    onQRCodeScanned: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    AndroidView(
        factory = { ctx ->
            var lastScannedText: String? = null

            DecoratedBarcodeView(ctx).apply {
                barcodeView.decoderFactory = DefaultDecoderFactory(listOf(BarcodeFormat.QR_CODE))
                setStatusText(context.getString(R.string.qr_scan_prompt))
                contentDescription = context.getString(R.string.cd_camera_view)

                // Add extra padding to the status text to ensure it's well clear of the nav bar
                statusView?.setPadding(0, 0, 0, 48)

                val callback = object : BarcodeCallback {
                    override fun barcodeResult(result: BarcodeResult) {
                        val resultText = result.text
                        if (resultText == null || resultText == lastScannedText) {
                            // Prevent duplicate scans
                            return
                        }

                        lastScannedText = resultText
                        Log.i(TAG, "Scanned: $resultText")

                        if (resultText.startsWith(Constants.QR_CODE_URL_PREFIX)) {
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
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars),
        onRelease = { barcodeView ->
            barcodeView.pause()
        }
    )
}
