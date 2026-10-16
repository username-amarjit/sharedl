package com.example.sharedl.ui

import android.content.ActivityNotFoundException
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.example.sharedl.R
import com.example.sharedl.download.DownloadMode
import com.example.sharedl.download.Downloads
import com.example.sharedl.download.YtDlp
import com.example.sharedl.download.createdAt
import com.example.sharedl.download.mode
import com.example.sharedl.download.sourceUrl
import com.example.sharedl.settings.AudioFormat
import com.example.sharedl.settings.Settings
import com.example.sharedl.settings.VideoQuality
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    val context = LocalContext.current
    val workManager = remember(context) { WorkManager.getInstance(context) }
    val settings = remember(context) { Settings(context) }
    val downloads by remember(workManager) { workManager.getWorkInfosByTagFlow(Downloads.TAG_ALL) }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val newestFirst = remember(downloads) { downloads.sortedByDescending { it.createdAt() } }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name)) }) },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item { HowItWorks() }
            item { LinkInput(context) }
            item {
                SectionHeader(stringResource(R.string.section_downloads)) {
                    if (newestFirst.any { it.state.isFinished }) {
                        TextButton(onClick = { workManager.pruneWork() }) {
                            Text(stringResource(R.string.action_clear_finished))
                        }
                    }
                }
            }
            if (newestFirst.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.downloads_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(newestFirst, key = { it.id.toString() }) { info ->
                    DownloadRow(
                        info = info,
                        onCancel = { workManager.cancelWorkById(info.id) },
                        onRetry = { info.sourceUrl()?.let { Downloads.enqueue(context, it, info.mode()) } },
                        onOpen = { openDownloadedFile(context, info) },
                    )
                }
            }
            item { SettingsSection(settings) }
            item { YtDlpSection(settings) }
        }
    }
}

@Composable
private fun HowItWorks() {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.how_title), style = MaterialTheme.typography.titleLarge)
            Step(1, R.string.how_step_1)
            Step(2, R.string.how_step_2)
            Step(3, R.string.how_step_3)
        }
    }
}

@Composable
private fun Step(number: Int, @StringRes text: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = number.toString(),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(stringResource(text), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun LinkInput(context: Context) {
    val clipboard = LocalClipboardManager.current
    var text by rememberSaveable { mutableStateOf("") }
    var showError by rememberSaveable { mutableStateOf(false) }

    fun submit(mode: DownloadMode) {
        if (Downloads.enqueueFromText(context, text, mode)) {
            text = ""
            showError = false
        } else {
            showError = true
        }
    }

    val errorText: (@Composable () -> Unit)? =
        if (showError) { { Text(stringResource(R.string.link_error)) } } else null

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = {
                text = it
                showError = false
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.link_label)) },
            placeholder = { Text("https://") },
            singleLine = true,
            isError = showError,
            supportingText = errorText,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { submit(DownloadMode.AUTO) }),
            trailingIcon = {
                TextButton(onClick = {
                    clipboard.getText()?.text?.let {
                        text = it.trim()
                        showError = false
                    }
                }) { Text(stringResource(R.string.action_paste)) }
            },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { submit(DownloadMode.AUTO) }, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.action_download))
            }
            FilledTonalButton(onClick = { submit(DownloadMode.AUDIO) }, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.action_download_audio))
            }
        }
    }
}

@Composable
private fun DownloadRow(info: WorkInfo, onCancel: () -> Unit, onRetry: () -> Unit, onOpen: () -> Unit) {
    val title = info.outputData.getString(Downloads.KEY_TITLE)
        ?: info.progress.getString(Downloads.KEY_TITLE)
        ?: info.sourceUrl()?.let { Uri.parse(it).host }
        ?: stringResource(R.string.download_untitled)
    val percent = info.progress.getInt(Downloads.KEY_PROGRESS, -1)

    val status = when (info.state) {
        WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> stringResource(R.string.status_queued)
        WorkInfo.State.RUNNING ->
            if (percent < 0) stringResource(R.string.status_preparing)
            else stringResource(R.string.status_percent, percent)
        WorkInfo.State.SUCCEEDED -> {
            val folder = info.outputData.getString(Downloads.KEY_FOLDER).orEmpty()
            val count = info.outputData.getInt(Downloads.KEY_COUNT, 1)
            if (count > 1) stringResource(R.string.saved_many_to, count, folder)
            else stringResource(R.string.saved_to, folder)
        }
        WorkInfo.State.FAILED ->
            info.outputData.getString(Downloads.KEY_ERROR) ?: stringResource(R.string.error_generic)
        WorkInfo.State.CANCELLED -> stringResource(R.string.status_cancelled)
    }

    ListItem(
        headlineContent = { Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = status,
                    color = if (info.state == WorkInfo.State.FAILED) {
                        MaterialTheme.colorScheme.error
                    } else {
                        LocalContentColor.current
                    },
                )
                if (info.state == WorkInfo.State.RUNNING) {
                    if (percent < 0) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(progress = { percent / 100f }, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        },
        trailingContent = {
            when (info.state) {
                WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED, WorkInfo.State.RUNNING ->
                    IconButton(onClick = onCancel) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_cancel))
                    }
                WorkInfo.State.SUCCEEDED ->
                    IconButton(onClick = onOpen) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = stringResource(R.string.action_open))
                    }
                WorkInfo.State.FAILED, WorkInfo.State.CANCELLED ->
                    IconButton(onClick = onRetry) {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.action_retry))
                    }
            }
        },
    )
}

@Composable
private fun SettingsSection(settings: Settings) {
    var quality by remember { mutableStateOf(settings.videoQuality) }
    var audioFormat by remember { mutableStateOf(settings.audioFormat) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeader(stringResource(R.string.section_settings))

        Text(stringResource(R.string.setting_video_quality), style = MaterialTheme.typography.titleSmall)
        ChoiceRow(VideoQuality.entries, quality, label = { stringResource(it.label) }) {
            quality = it
            settings.videoQuality = it
        }

        Text(stringResource(R.string.setting_audio_format), style = MaterialTheme.typography.titleSmall)
        ChoiceRow(AudioFormat.entries, audioFormat, label = { stringResource(it.label) }) {
            audioFormat = it
            settings.audioFormat = it
        }
        Text(
            text = stringResource(R.string.setting_audio_format_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> ChoiceRow(
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, option ->
            SegmentedButton(
                selected = option == selected,
                onClick = { onSelect(option) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
            ) {
                Text(label(option))
            }
        }
    }
}

@Composable
private fun YtDlpSection(settings: Settings) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var version by remember { mutableStateOf<String?>(null) }
    var updating by remember { mutableStateOf(false) }
    var resultMessage by remember { mutableStateOf<String?>(null) }
    var nightly by remember { mutableStateOf(settings.nightlyUpdates) }

    LaunchedEffect(Unit) {
        version = withContext(Dispatchers.IO) { YtDlp.version(context) }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader(stringResource(R.string.ytdlp_title))
        Text(
            text = version?.let { stringResource(R.string.ytdlp_version, it) }
                ?: stringResource(R.string.ytdlp_version_loading),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = stringResource(R.string.ytdlp_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.setting_nightly),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = nightly,
                onCheckedChange = {
                    nightly = it
                    settings.nightlyUpdates = it
                },
            )
        }
        Button(
            enabled = !updating,
            onClick = {
                updating = true
                resultMessage = null
                scope.launch {
                    val result = withContext(Dispatchers.IO) { YtDlp.update(context, nightly) }
                    if (result == YtDlp.UpdateResult.UPDATED) {
                        version = withContext(Dispatchers.IO) { YtDlp.version(context) }
                    }
                    resultMessage = context.getString(
                        when (result) {
                            YtDlp.UpdateResult.UPDATED -> R.string.ytdlp_updated
                            YtDlp.UpdateResult.ALREADY_LATEST -> R.string.ytdlp_up_to_date
                            YtDlp.UpdateResult.FAILED -> R.string.ytdlp_update_failed
                        }
                    )
                    updating = false
                }
            },
        ) {
            Text(stringResource(if (updating) R.string.action_updating else R.string.action_update_ytdlp))
        }
        resultMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun SectionHeader(title: String, action: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        action()
    }
}

private fun openDownloadedFile(context: Context, info: WorkInfo) {
    // Only ever open the content:// URI we wrote ourselves.
    val uri = info.outputData.getString(Downloads.KEY_URI)
        ?.let(Uri::parse)
        ?.takeIf { it.scheme == ContentResolver.SCHEME_CONTENT }
        ?: return
    val mime = info.outputData.getString(Downloads.KEY_MIME) ?: "*/*"
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, mime)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, R.string.toast_no_player, Toast.LENGTH_SHORT).show()
    }
}
