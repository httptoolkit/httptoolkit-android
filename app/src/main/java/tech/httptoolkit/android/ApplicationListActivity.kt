package tech.httptoolkit.android

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.preference.PreferenceManager
import android.view.MenuItem
import android.view.View
import android.widget.PopupMenu
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import kotlinx.android.synthetic.main.activity_app_list.*
import kotlinx.coroutines.*
import java.util.*
import kotlin.collections.ArrayList

class ApplicationListActivity : AppCompatActivity(), SwipeRefreshLayout.OnRefreshListener,
    CoroutineScope by MainScope(), PopupMenu.OnMenuItemClickListener, View.OnClickListener {
    private val sharedPreferences by lazy {
        getWhiteListAppSharedPreferences(this)
    }

    private val allApps = ArrayList<PackageInfo>()
    private val filteredApps = ArrayList<PackageInfo>()

    private var showSystem = false
    private var textFilter = ""


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_list)

        apps_list_recyclerView.adapter =
            ApplicationListAdapter(
                filteredApps,
                { sharedPreferences.contains(it) },
                { pInfo, isChecked ->
                    if (isChecked)
                        sharedPreferences.edit().putString(pInfo.packageName, "").apply()
                    else
                        sharedPreferences.edit().remove(pInfo.packageName).apply()
                })
        apps_list_swipeRefreshLayout.setOnRefreshListener(this)
        apps_list_more_menu.setOnClickListener(this)

        apps_list_filterEditText.doAfterTextChanged {
            textFilter = it.toString()
            applyFilters()
        }

        onRefresh()
    }

    override fun onRefresh() {
        launch(Dispatchers.Main) {
            if (apps_list_swipeRefreshLayout.isRefreshing.not()) {
                apps_list_swipeRefreshLayout.isRefreshing = true
            }

            val apps = loadAllApps()
            allApps.clear()
            allApps.addAll(apps)
            applyFilters()

            apps_list_swipeRefreshLayout.isRefreshing = false
        }
    }

    private fun applyFilters() {
        filteredApps.clear()
        filteredApps.addAll(allApps.filter(::matchesFilters))
        apps_list_recyclerView.adapter?.notifyDataSetChanged()
    }

    private fun matchesFilters(app: PackageInfo): Boolean {
        val appInfo = app.applicationInfo
        val appLabel = appInfo.loadLabel(packageManager)
        val isSystemApp = appInfo.flags and ApplicationInfo.FLAG_SYSTEM == 1

        return (textFilter.isEmpty() || appLabel.contains(textFilter, true)) &&
            (showSystem || !isSystemApp)
    }

    private suspend fun loadAllApps(): List<PackageInfo> =
        withContext(Dispatchers.IO) {
            return@withContext packageManager.getInstalledPackages(PackageManager.GET_META_DATA).apply {
                sortBy {
                    it.applicationInfo.loadLabel(packageManager).toString().toUpperCase(
                        Locale.getDefault()
                    )
                }
            }
        }

    override fun onMenuItemClick(item: MenuItem?): Boolean {
        return when (item?.itemId) {
            R.id.action_show_system -> {
                showSystem = showSystem.not()
                applyFilters()
                true
            }
            else -> false
        }
    }

    override fun onClick(v: View?) {
        when (v?.id) {
            R.id.apps_list_more_menu -> {
                PopupMenu(this, apps_list_more_menu).apply {
                    this.inflate(R.menu.menu_app_list)
                    this.menu.findItem(R.id.action_show_system).isChecked = showSystem
                    this.setOnMenuItemClickListener(this@ApplicationListActivity)
                }.show()
            }
        }
    }

    companion object {
        fun getWhiteListAppSharedPreferences(ctx: Context): SharedPreferences =
            ctx.getSharedPreferences("whiteListedApps", Context.MODE_PRIVATE)
    }

    override fun onBackPressed() {
        Toast.makeText(this, "Disconnect and Connect again to take effect.", Toast.LENGTH_LONG)
            .show()
        super.onBackPressed()
    }
}