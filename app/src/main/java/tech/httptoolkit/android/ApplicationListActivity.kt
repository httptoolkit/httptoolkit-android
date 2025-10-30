package tech.httptoolkit.android

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.PopupMenu
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.widget.doAfterTextChanged
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import kotlinx.coroutines.*
import tech.httptoolkit.android.databinding.AppsListBinding
import java.util.*

class ApplicationListActivity : AppCompatActivity(), SwipeRefreshLayout.OnRefreshListener,
    CoroutineScope by MainScope(), PopupMenu.OnMenuItemClickListener, View.OnClickListener {

    private lateinit var binding: AppsListBinding

    private val allApps = ArrayList<PackageInfo>()
    private val filteredApps = ArrayList<PackageInfo>()

    private lateinit var blockedPackages: MutableSet<String>

    private var showSystem = false
    private var showEnabledOnly = false
    private var textFilter = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        blockedPackages = intent.getStringArrayExtra(IntentExtras.UNSELECTED_APPS_EXTRA)!!.toHashSet()
        binding = AppsListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.appsListRecyclerView.adapter =
            ApplicationListAdapter(
                filteredApps,
                ::isAppEnabled,
                ::setAppEnabled
            )
        binding.appsListSwipeRefreshLayout.setOnRefreshListener(this)
        binding.appsListMoreMenu.setOnClickListener(this)

        binding.appsListFilterEditText.doAfterTextChanged {
            textFilter = it.toString()
            applyFilters()
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                setResult(RESULT_OK, Intent().putExtra(
                    IntentExtras.UNSELECTED_APPS_EXTRA,
                    blockedPackages.toTypedArray()
                ))
                finish()
            }
        })

        onRefresh()
    }

    override fun onRefresh() {
        launch(Dispatchers.Main) {
            if (binding.appsListSwipeRefreshLayout.isRefreshing.not()) {
                binding.appsListSwipeRefreshLayout.isRefreshing = true
            }

            val apps = loadAllApps()
            allApps.clear()
            allApps.addAll(apps)
            applyFilters()

            binding.appsListSwipeRefreshLayout.isRefreshing = false
        }
    }

    private fun applyFilters() {
        filteredApps.clear()
        filteredApps.addAll(allApps.filter(::matchesFilters))
        binding.appsListRecyclerView.adapter?.notifyDataSetChanged()
    }

    private fun matchesFilters(app: PackageInfo): Boolean {
        val appInfo = app.applicationInfo ?: return false
        val appLabel = AppLabelCache.getAppLabel(packageManager, appInfo)
        val isSystemApp = appInfo.flags and ApplicationInfo.FLAG_SYSTEM == 1

        return (textFilter.isEmpty() || appLabel.contains(textFilter, true)) && // Filter by name
            (showSystem || !isSystemApp) && // Show system apps, if that's active
            (!showEnabledOnly || isAppEnabled(app)) && // Only show enabled apps, if that's active
            app.packageName != packageName // Never show ourselves
    }

    private fun isAppEnabled(app: PackageInfo): Boolean {
        return !blockedPackages.contains(app.packageName)
    }

    private fun setAppEnabled(app: PackageInfo, isEnabled: Boolean) {
        val wasChanged = if (!isEnabled) {
            blockedPackages.add(app.packageName)
        } else {
            blockedPackages.remove(app.packageName)
        }

        if (wasChanged && showEnabledOnly) applyFilters()
    }

    private suspend fun loadAllApps(): List<PackageInfo> =
        withContext(Dispatchers.IO) {
            return@withContext packageManager.getInstalledPackages(PackageManager.GET_META_DATA)
                .filter { pkg ->
                    pkg.applicationInfo != null
                }.sortedBy { pkg ->
                    AppLabelCache.getAppLabel(packageManager, pkg.applicationInfo!!).uppercase(
                        Locale.getDefault()
                    )
                }
        }

    override fun onMenuItemClick(item: MenuItem?): Boolean {
        return when (item?.itemId) {
            R.id.action_show_system -> {
                showSystem = showSystem.not()
                applyFilters()
                true
            }
            R.id.action_show_enabled -> {
                showEnabledOnly = showEnabledOnly.not()
                applyFilters()
                true
            }
            R.id.action_toggle_all -> {
                if (blockedPackages.isEmpty()) {
                    // If everything is enabled, disable everything
                    blockedPackages.addAll(allApps.map { app -> app.packageName })
                } else {
                    // Otherwise, re-enable everything
                    blockedPackages.removeAll(allApps.map { app -> app.packageName })
                }

                if (showEnabledOnly) {
                    applyFilters()
                } else {
                    binding.appsListRecyclerView.adapter?.notifyDataSetChanged()
                }
                true
            }
            else -> false
        }
    }

    override fun onClick(v: View?) {
        when (v?.id) {
            R.id.apps_list_more_menu -> {
                PopupMenu(ContextThemeWrapper(this, R.style.PopupMenu), binding.appsListMoreMenu).apply {
                    this.inflate(R.menu.menu_app_list)
                    this.menu.findItem(R.id.action_show_system).isChecked = showSystem
                    this.menu.findItem(R.id.action_show_enabled).isChecked = showEnabledOnly
                    this.menu.findItem(R.id.action_toggle_all).title = getString(
                        if (blockedPackages.isEmpty())
                            R.string.disable_all
                        else
                            R.string.enable_all
                    )
                    this.setOnMenuItemClickListener(this@ApplicationListActivity)
                }.show()
            }
        }
    }
}