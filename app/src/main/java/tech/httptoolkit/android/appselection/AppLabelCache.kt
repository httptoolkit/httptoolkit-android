package tech.httptoolkit.android.appselection

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager

object AppLabelCache {

    private val cache = HashMap<String, String>() // PackageName -> Label

    fun getAppLabel(packageManager: PackageManager, applicationInfo: ApplicationInfo): String {
        return cache.getOrPut(applicationInfo.packageName) {
            applicationInfo.loadLabel(packageManager).toString()
        }
    }

}