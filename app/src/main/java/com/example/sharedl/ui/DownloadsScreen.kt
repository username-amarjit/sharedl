package com.example.sharedl.ui

import android.content.Context
import android.net.Uri
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
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.example.sharedl.R
import com.example.sharedl.download.DownloadMode
import com.example.sharedl.download.Downloads
import com.example.sharedl.download.createdAt
import com.example.sharedl.download.mode
import com.example.sharedl.download.sourceUrl
import com.example.sharedl.download.toProgressSnapshot
import com.example.sharedl.util.Formats
import com.example.sharedl.util.MediaIntents

@Composable
fun DownloadsScreen(downloads: List<WorkInfo>, workManager: WorkManager, contentPadding: PaddingValues) {
    val context = LocalContext.current
    val newestFirst = remember(downloads) { downloads.sortedByDescending { it.createdAt() } }
    var shownDetails by remember { mutableStateOf<String?>(null) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .imePadding(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { LinkInput(context) }
        if (newestFirst.isEmpty()) {
            item { HowItWorks() }
        } else {
            item {
                SectionHeader(stringResource(R.string.section_downloads)) {
                    if (newestFirst.any { it.state.isFinished }) {
                        TextButton(onClick = { workManager.pruneWork() }) {
                            Text(stringResource(R.string.action_clear_finished))
                        }
                    }
                }
            }
            items(newestFirst, key = { it.id.toString() }) { info ->
                DownloadRow(
                    info = info,
                    onCancel = { workManager.cancelWorkById(info.id) },
                    onRetry = { info.sourceUrl()?.let { Downloads.enqueue(context, it, info.mode()) } },
                    onOpen = {
                        val uri = info.outputData.getString(Downloads.KEY_URI)?.let(Uri::parse)
                        if (uri != null) MediaIntents.open(context, uri, info.outputData.getString(Downloads.KEY_MIME))
                    },
                    onDetails = { shownDetails = it },
                )
            }
        }
    }

    shownDetails?.let { details ->
        DetailsDialog(stringResource(R.string.details_title), details, onDismiss = { shownDetails = null })
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
private fun DownloadRow(
    info: WorkInfo,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onOpen: () -> Unit,
    onDetails: (String) -> Unit,
) {
    val context = LocalContext.current
    val running = info.state == WorkInfo.State.RUNNING
    val snapshot = info.progress.toProgressSnapshot()

    val title = info.outputData.getString(Downloads.KEY_TITLE)
        ?: info.progress.getString(Downloads.KEY_TITLE)
        ?: info.sourceUrl()?.let { Uri.parse(it).host }
        ?: stringResource(R.string.download_untitled)

    val status = when (info.state) {
        WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> stringResource(R.string.status_queued)
        WorkInfo.State.RUNNING -> Formats.progressHeadline(context, snapshot)
        WorkInfo.State.SUCCEEDED -> Formats.savedSummary(
            context,
            folder = info.outputData.getString(Downloads.KEY_FOLDER).orEmpty(),
            count = info.outputData.getInt(Downloads.KEY_COUNT, 1),
            sizeBytes = info.outputData.getLong(Downloads.KEY_SIZE, 0),
        )
        WorkInfo.State.FAILED ->
            info.outputData.getString(Downloads.KEY_ERROR) ?: stringResource(R.string.error_generic)
        WorkInfo.State.CANCELLED -> stringResource(R.string.status_cancelled)
    }
    val partial = info.state == WorkInfo.State.SUCCEEDED && info.outputData.getBoolean(Downloads.KEY_PARTIAL, false)
    val limited = info.state == WorkInfo.State.SUCCEEDED && info.outputData.getBoolean(Downloads.KEY_LIMITED, false)
    val detail = when {
        running -> Formats.progressDetail(context, snapshot)
        partial -> stringResource(R.string.partial_note)
        limited -> stringResource(R.string.limited_note)
        else -> null
    }
    val errorDetails = info.outputData.getString(Downloads.KEY_ERROR_DETAILS)
        ?.takeIf { info.state == WorkInfo.State.FAILED || partial }

    ListItem(
        headlineContent = { Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = status,
                        modifier = Modifier.weight(1f),
                        color = if (info.state == WorkInfo.State.FAILED) {
                            MaterialTheme.colorScheme.error
                        } else {
                            LocalContentColor.current
                        },
                    )
                    if (running && snapshot.percent >= 0) {
                        Text(stringResource(R.string.status_percent, snapshot.percent))
                    }
                }
                if (running) {
                    if (snapshot.percent < 0) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(
                            progress = { snapshot.percent / 100f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                if (detail != null) {
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (errorDetails != null) {
                    TextButton(
                        onClick = { onDetails(errorDetails) },
                        contentPadding = PaddingValues(horizontal = 0.dp),
                    ) { Text(stringResource(R.string.action_details)) }
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
internal fun SectionHeader(title: String, action: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        action()
    }
}
