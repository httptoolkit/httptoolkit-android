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

    private enum class SortMode {
        ASCENDING, DESCENDING
    }

    private val allApps = ArrayList<PackageInfo>()
    private var sortMode = SortMode.ASCENDING
    private var showSystem = false
    private var textFilter = ""


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_list)
        apps_list_recyclerView.adapter =
            ApplicationListAdapter(
                allApps,
                { sharedPreferences.contains(it) },
                { pInfo, isChecked ->
                    if (isChecked)
                        sharedPreferences.edit().putString(pInfo.packageName, "").apply()
                    else
                        sharedPreferences.edit().remove(pInfo.packageName).apply()
                })
        apps_list_swipeRefreshLayout.setOnRefreshListener(this)
        apps_list_sortBy.setOnClickListener(this)
        apps_list_more_menu.setOnClickListener(this)
        apps_list_filterEditText.doAfterTextChanged {
            textFilter = it.toString()
            onRefresh()
        }
        onRefresh()
    }

    override fun onRefresh() {
        launch(Dispatchers.Main) {
            if (apps_list_swipeRefreshLayout.isRefreshing.not())
                apps_list_swipeRefreshLayout.isRefreshing = true
            val apps = loadAllApps(showSystem, sortMode, textFilter)
            allApps.clear()
            allApps.addAll(apps)
            apps_list_recyclerView.adapter?.notifyDataSetChanged()
            apps_list_swipeRefreshLayout.isRefreshing = false
        }
    }

    private suspend fun loadAllApps(
        showSystem: Boolean,
        sortMode: SortMode,
        filterByText: String
    ): List<PackageInfo> =
        withContext(Dispatchers.IO) {
            return@withContext packageManager.getInstalledPackages(PackageManager.GET_META_DATA)
                .run { //Text Filter
                    if (filterByText.isBlank())
                        this
                    else
                        this.filter {
                            it.applicationInfo.loadLabel(packageManager)
                                .contains(filterByText, true)
                        }.toMutableList()
                }.run { //System App Info
                    if (showSystem)
                        this
                    else this.filter {
                        it.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 1
                    }.toMutableList()
                }.apply { // Sorting Order
                    if (sortMode == SortMode.ASCENDING)
                        sortBy {
                            it.applicationInfo.loadLabel(packageManager).toString().toUpperCase(
                                Locale.getDefault()
                            )
                        }
                    else {
                        sortByDescending {
                            it.applicationInfo.loadLabel(packageManager).toString()
                                .toUpperCase(Locale.getDefault())
                        }
                    }
                }
        }

    override fun onMenuItemClick(item: MenuItem?): Boolean {
        return when (item?.itemId) {
            R.id.action_show_system -> {
                showSystem = showSystem.not()
                onRefresh()
                true
            }
            else -> false
        }
    }

    override fun onClick(v: View?) {
        when (v?.id) {
            R.id.apps_list_sortBy -> {
                sortMode =
                    if (sortMode == SortMode.ASCENDING) SortMode.DESCENDING else SortMode.ASCENDING
                onRefresh()
            }
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