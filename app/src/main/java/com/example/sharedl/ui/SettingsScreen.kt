package com.example.sharedl.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.sharedl.R
import com.example.sharedl.download.Ffmpeg
import com.example.sharedl.download.YtDlp
import com.example.sharedl.settings.AudioFormat
import com.example.sharedl.settings.Settings
import com.example.sharedl.settings.VideoQuality
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(contentPadding: PaddingValues) {
    val context = LocalContext.current
    val settings = remember(context) { Settings(context) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        item { FormatSettings(settings) }
        item { YtDlpSettings(settings) }
        item { FfmpegStatus() }
    }
}

@Composable
private fun FormatSettings(settings: Settings) {
    var quality by remember { mutableStateOf(settings.videoQuality) }
    var audioFormat by remember { mutableStateOf(settings.audioFormat) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
private fun YtDlpSettings(settings: Settings) {
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
private fun FfmpegStatus() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<Ffmpeg.Status?>(null) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        status = withContext(Dispatchers.IO) { Ffmpeg.status(context) }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader(stringResource(R.string.ffmpeg_title))
        status?.let {
            val ready = it.state == Ffmpeg.State.READY
            Text(
                text = stringResource(
                    when (it.state) {
                        Ffmpeg.State.READY -> R.string.ffmpeg_ok
                        Ffmpeg.State.MISSING -> R.string.ffmpeg_missing
                        Ffmpeg.State.PAGE_SIZE -> R.string.ffmpeg_page_size
                        Ffmpeg.State.BROKEN -> R.string.ffmpeg_broken
                    }
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = if (ready) LocalContentColor.current else MaterialTheme.colorScheme.error,
            )
            if (!ready) {
                Text(
                    text = stringResource(R.string.ffmpeg_fallback),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Text(
                text = stringResource(R.string.ffmpeg_device, it.abis, it.pageSizeKb),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        OutlinedButton(
            enabled = !testing,
            onClick = {
                testing = true
                scope.launch {
                    testResult = withContext(Dispatchers.IO) { Ffmpeg.diagnose(context) }
                    testing = false
                }
            },
        ) {
            Text(stringResource(if (testing) R.string.ffmpeg_testing else R.string.action_test_ffmpeg))
        }
    }

    testResult?.let { result ->
        DetailsDialog(stringResource(R.string.ffmpeg_test_title), result, onDismiss = { testResult = null })
    }
}
