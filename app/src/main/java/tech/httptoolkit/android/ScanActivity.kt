package tech.httptoolkit.android

import android.Manifest
import android.content.pm.PackageManager
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.util.Log
import com.google.zxing.BarcodeFormat
import com.google.zxing.Result
import me.dm7.barcodescanner.zxing.ZXingScannerView


class ScanActivity : AppCompatActivity(), ZXingScannerView.ResultHandler {

    private val TAG = ScanActivity::class.simpleName
    private var app: HttpToolkitApplication? = null

    private var scannerView: ZXingScannerView? = null

    public override fun onCreate(state: Bundle?) {
        super.onCreate(state)

        val canUseCamera = checkSelfPermission(Manifest.permission.CAMERA)
        if (canUseCamera != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 0)
            // Until confirmed, the activity will show as empty, switching to the
            // camera as soon as permission is accepted.
        }
        scannerView = ZXingScannerView(this)
        scannerView!!.setFormats(listOf(BarcodeFormat.QR_CODE))
        setContentView(scannerView)
        app = this.application as HttpToolkitApplication
    }

    public override fun onResume() {
        super.onResume()

        app!!.trackScreen("Scan")
        scannerView!!.setResultHandler(this)
        scannerView!!.startCamera()
    }

    public override fun onPause() {
        super.onPause()

        app!!.clearScreen()
        scannerView!!.stopCamera()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        Log.i(TAG, requestCode.toString() + permissions.toString() + grantResults.toString())
    }

    override fun handleResult(rawResult: Result) {
        val url = rawResult.text
        Log.v(TAG, "Scanned $url")
        app!!.trackEvent("Setup", "tag-scanned")
    }
}