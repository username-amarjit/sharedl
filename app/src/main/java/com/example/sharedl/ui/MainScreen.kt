package com.example.sharedl.ui

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.WorkManager
import com.example.sharedl.R
import com.example.sharedl.download.Downloads

private enum class Tab(@StringRes val label: Int, val icon: ImageVector) {
    DOWNLOADS(R.string.tab_downloads, Icons.Filled.Download),
    LIBRARY(R.string.tab_library, Icons.Filled.VideoLibrary),
    SETTINGS(R.string.tab_settings, Icons.Filled.Settings),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    val context = LocalContext.current
    val workManager = remember(context) { WorkManager.getInstance(context) }
    val downloads by remember(workManager) { workManager.getWorkInfosByTagFlow(Downloads.TAG_ALL) }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val activeCount = downloads.count { !it.state.isFinished }
    var tab by rememberSaveable { mutableStateOf(Tab.DOWNLOADS) }

    Scaffold(
        topBar = {
            TopAppBar(title = {
                Text(stringResource(if (tab == Tab.DOWNLOADS) R.string.app_name else tab.label))
            })
        },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { item ->
                    NavigationBarItem(
                        selected = tab == item,
                        onClick = { tab = item },
                        icon = {
                            // Running downloads show as a count, so you can tell from the other tabs.
                            if (item == Tab.DOWNLOADS && activeCount > 0) {
                                BadgedBox(badge = { Badge { Text(activeCount.toString()) } }) {
                                    Icon(item.icon, contentDescription = null)
                                }
                            } else {
                                Icon(item.icon, contentDescription = null)
                            }
                        },
                        label = { Text(stringResource(item.label)) },
                    )
                }
            }
        },
    ) { innerPadding ->
        when (tab) {
            Tab.DOWNLOADS -> DownloadsScreen(downloads, workManager, innerPadding)
            Tab.LIBRARY -> LibraryScreen(innerPadding)
            Tab.SETTINGS -> SettingsScreen(innerPadding)
        }
    }
}
