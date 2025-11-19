package tech.httptoolkit.android.appselection

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.google.accompanist.drawablepainter.rememberDrawablePainter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tech.httptoolkit.android.R

@Composable
fun AppListScreen(
    initialBlockedPackages: Set<String>,
    onBlockedPackagesChanged: (Set<String>) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val packageManager = context.packageManager

    var allApps by remember { mutableStateOf<List<PackageInfo>>(emptyList()) }
    var filteredApps by remember { mutableStateOf<List<PackageInfo>>(emptyList()) }
    var blockedPackages by remember { mutableStateOf(initialBlockedPackages) }
    var isLoading by remember { mutableStateOf(true) }
    var textFilter by remember { mutableStateOf("") }
    var showSystem by remember { mutableStateOf(false) }
    var showEnabledOnly by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }

    // Notify parent of changes
    LaunchedEffect(blockedPackages) {
        onBlockedPackagesChanged(blockedPackages)
    }

    // Load apps on first composition
    LaunchedEffect(Unit) {
        isLoading = true
        allApps = loadAllApps(packageManager, context.packageName)
        isLoading = false
    }

    // Apply filters whenever inputs change
    LaunchedEffect(allApps, textFilter, showSystem, showEnabledOnly, blockedPackages) {
        filteredApps = allApps.filter { app ->
            matchesFilters(
                app = app,
                packageManager = packageManager,
                textFilter = textFilter,
                showSystem = showSystem,
                showEnabledOnly = showEnabledOnly,
                blockedPackages = blockedPackages
            )
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars.only(WindowInsetsSides.Top))
            .padding(horizontal = 16.dp)
    ) {
        // Search and menu card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .zIndex(1f),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextField(
                    value = textFilter,
                    onValueChange = { textFilter = it },
                    placeholder = { Text(stringResource(R.string.all_applications)) },
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = 8.dp),
                    singleLine = true,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent
                    )
                )

                Box {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = stringResource(R.string.cd_more_options)
                        )
                    }

                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(
                                        if (blockedPackages.isEmpty()) R.string.disable_all
                                        else R.string.enable_all
                                    )
                                )
                            },
                            onClick = {
                                showMenu = false
                                blockedPackages = if (blockedPackages.isEmpty()) {
                                    // Disable all
                                    allApps.map { it.packageName }.toSet()
                                } else {
                                    // Enable all
                                    emptySet()
                                }
                            }
                        )

                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(
                                        checked = showEnabledOnly,
                                        onCheckedChange = null
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(stringResource(R.string.show_enabled))
                                }
                            },
                            onClick = {
                                showEnabledOnly = !showEnabledOnly
                            }
                        )

                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(
                                        checked = showSystem,
                                        onCheckedChange = null
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(stringResource(R.string.show_system))
                                }
                            },
                            onClick = {
                                showSystem = !showSystem
                            }
                        )
                    }
                }
            }
        }

        // App list with pull-to-refresh
        if (isLoading) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                items(
                    items = filteredApps,
                    key = { it.packageName }
                ) { app ->
                    AppListItem(
                        packageInfo = app,
                        packageManager = packageManager,
                        isEnabled = !blockedPackages.contains(app.packageName),
                        onEnabledChange = { isEnabled ->
                            blockedPackages = if (isEnabled) {
                                blockedPackages - app.packageName
                            } else {
                                blockedPackages + app.packageName
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun AppListItem(
    packageInfo: PackageInfo,
    packageManager: PackageManager,
    isEnabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val appInfo = packageInfo.applicationInfo!!
    val appLabel = remember(packageInfo) {
        AppLabelCache.getAppLabel(packageManager, appInfo)
    }
    val appIcon: Drawable = remember(packageInfo) {
        appInfo.loadIcon(packageManager)
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp, horizontal = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp, horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // App icon
            Image(
                painter = rememberDrawablePainter(drawable = appIcon),
                contentDescription = stringResource(R.string.cd_app_icon, appLabel),
                modifier = Modifier
                    .size(72.dp)
                    .padding(vertical = 8.dp)
            )

            // App name and package
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp)
            ) {
                Text(
                    text = appLabel,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = packageInfo.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Enable/disable switch
            Switch(
                checked = isEnabled,
                onCheckedChange = onEnabledChange,
                modifier = Modifier
                    .padding(end = 8.dp)
            )
        }
    }
}

private suspend fun loadAllApps(
    packageManager: PackageManager,
    currentPackageName: String
): List<PackageInfo> = withContext(Dispatchers.IO) {
    packageManager.getInstalledPackages(PackageManager.GET_META_DATA)
        .filter { pkg ->
            pkg.applicationInfo != null
        }
        .sortedBy { pkg ->
            AppLabelCache.getAppLabel(packageManager, pkg.applicationInfo!!).uppercase()
        }
}

private fun matchesFilters(
    app: PackageInfo,
    packageManager: PackageManager,
    textFilter: String,
    showSystem: Boolean,
    showEnabledOnly: Boolean,
    blockedPackages: Set<String>
): Boolean {
    val appInfo = app.applicationInfo ?: return false
    val appLabel = AppLabelCache.getAppLabel(packageManager, appInfo)
    val isSystemApp = (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
    val isEnabled = !blockedPackages.contains(app.packageName)

    return (textFilter.isEmpty() || appLabel.contains(textFilter, ignoreCase = true)) &&
            (showSystem || !isSystemApp) &&
            (!showEnabledOnly || isEnabled)
}
