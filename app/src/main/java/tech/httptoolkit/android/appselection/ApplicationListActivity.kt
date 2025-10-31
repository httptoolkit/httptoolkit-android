package tech.httptoolkit.android.appselection

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import tech.httptoolkit.android.IntentExtras
import tech.httptoolkit.android.ui.HttpToolkitTheme

class ApplicationListActivity : ComponentActivity() {

    private var currentBlockedPackages: Set<String> = emptySet()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val initialBlockedPackages = intent.getStringArrayExtra(IntentExtras.UNSELECTED_APPS_EXTRA)!!.toSet()
        currentBlockedPackages = initialBlockedPackages

        setContent {
            HttpToolkitTheme {
                AppListScreen(
                    initialBlockedPackages = initialBlockedPackages,
                    onBlockedPackagesChanged = { newBlockedPackages ->
                        currentBlockedPackages = newBlockedPackages
                    }
                )
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                setResult(RESULT_OK, Intent().putExtra(
                    IntentExtras.UNSELECTED_APPS_EXTRA,
                    currentBlockedPackages.toTypedArray()
                ))
                finish()
            }
        })
    }
}