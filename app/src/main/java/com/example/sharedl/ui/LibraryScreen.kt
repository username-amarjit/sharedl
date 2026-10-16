package com.example.sharedl.ui

import android.text.format.DateUtils
import android.util.Size
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.sharedl.R
import com.example.sharedl.library.MediaFile
import com.example.sharedl.library.MediaLibrary
import com.example.sharedl.util.Formats
import com.example.sharedl.util.MediaIntents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class LibraryFilter(@StringRes val label: Int, @StringRes val empty: Int) {
    ALL(R.string.filter_all, R.string.library_empty),
    VIDEOS(R.string.filter_videos, R.string.library_empty_videos),
    AUDIO(R.string.filter_audio, R.string.library_empty_audio);

    fun matches(file: MediaFile) = when (this) {
        ALL -> true
        VIDEOS -> file.isVideo
        AUDIO -> !file.isVideo
    }
}

private enum class LibrarySort(@StringRes val label: Int) {
    NEWEST(R.string.sort_newest),
    LARGEST(R.string.sort_largest),
}

@Composable
fun LibraryScreen(contentPadding: PaddingValues) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val files by remember(context) { MediaLibrary.observe(context) }
        .collectAsStateWithLifecycle(initialValue = null)
    var filter by rememberSaveable { mutableStateOf(LibraryFilter.ALL) }
    var sort by rememberSaveable { mutableStateOf(LibrarySort.NEWEST) }
    var pendingDelete by remember { mutableStateOf<MediaFile?>(null) }

    val all = files
    if (all == null) {
        Box(Modifier.fillMaxSize().padding(contentPadding), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    val visible = remember(all, filter, sort) {
        val filtered = all.filter { file -> filter.matches(file) }
        // The library already arrives newest first.
        if (sort == LibrarySort.LARGEST) filtered.sortedByDescending { it.sizeBytes } else filtered
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (all.isEmpty()) {
            item { EmptyText(R.string.library_empty) }
            return@LazyColumn
        }
        item { LibrarySummary(all) }
        item {
            FilterRow(
                filter = filter,
                onFilter = { filter = it },
                sort = sort,
                onToggleSort = { sort = if (sort == LibrarySort.NEWEST) LibrarySort.LARGEST else LibrarySort.NEWEST },
            )
        }
        if (visible.isEmpty()) {
            item { EmptyText(filter.empty) }
        } else {
            items(visible, key = { it.id }) { file ->
                LibraryRow(
                    file = file,
                    onOpen = { MediaIntents.open(context, file.uri, file.mimeType) },
                    onShare = { MediaIntents.share(context, file.uri, file.mimeType, file.title) },
                    onDelete = { pendingDelete = file },
                )
            }
        }
    }

    pendingDelete?.let { file ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.delete_title)) },
            text = { Text(stringResource(R.string.delete_message, file.title)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    scope.launch {
                        // The list refreshes on its own once MediaStore reports the change.
                        val deleted = withContext(Dispatchers.IO) { MediaLibrary.delete(context, file) }
                        if (!deleted) Toast.makeText(context, R.string.toast_delete_failed, Toast.LENGTH_SHORT).show()
                    }
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

@Composable
private fun LibrarySummary(files: List<MediaFile>) {
    val context = LocalContext.current
    val videoBytes = files.filter { it.isVideo }.sumOf { it.sizeBytes }
    val audioBytes = files.filterNot { it.isVideo }.sumOf { it.sizeBytes }

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(Formats.size(context, videoBytes + audioBytes), style = MaterialTheme.typography.headlineMedium)
            Text(
                text = pluralStringResource(R.plurals.file_count, files.size, files.size),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = stringResource(
                    R.string.library_breakdown,
                    Formats.size(context, videoBytes),
                    Formats.size(context, audioBytes),
                ),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterRow(
    filter: LibraryFilter,
    onFilter: (LibraryFilter) -> Unit,
    sort: LibrarySort,
    onToggleSort: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        LibraryFilter.entries.forEach { option ->
            FilterChip(
                selected = filter == option,
                onClick = { onFilter(option) },
                label = { Text(stringResource(option.label)) },
            )
        }
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onToggleSort) { Text(stringResource(sort.label)) }
    }
}

@Composable
private fun LibraryRow(file: MediaFile, onOpen: () -> Unit, onShare: () -> Unit, onDelete: () -> Unit) {
    val context = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }
    val details = remember(file) {
        buildList {
            add(Formats.size(context, file.sizeBytes))
            if (file.durationMs > 0) add(Formats.duration(file.durationMs / 1000))
            add(
                DateUtils.getRelativeTimeSpanString(
                    file.dateAddedSeconds * 1000, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS,
                ).toString()
            )
        }.joinToString(", ")
    }

    ListItem(
        modifier = Modifier.clickable(onClick = onOpen),
        leadingContent = { Thumbnail(file) },
        headlineContent = { Text(file.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(details) },
        trailingContent = {
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.action_more))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_share)) },
                        leadingIcon = { Icon(Icons.Filled.Share, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onShare()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_delete)) },
                        leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onDelete()
                        },
                    )
                }
            }
        },
    )
}

/** Video frame or album art from Android's own thumbnail cache; an icon when there's none. */
@Composable
private fun Thumbnail(file: MediaFile) {
    val context = LocalContext.current
    val bitmap by produceState<ImageBitmap?>(initialValue = null, file.uri) {
        value = withContext(Dispatchers.IO) {
            try {
                context.contentResolver.loadThumbnail(file.uri, Size(320, 180), null).asImageBitmap()
            } catch (e: Exception) {
                null // audio without album art, or a file that was just deleted
            }
        }
    }
    Box(
        modifier = Modifier
            .size(width = 96.dp, height = 54.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        val image = bitmap
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                imageVector = if (file.isVideo) Icons.Filled.Movie else Icons.Filled.MusicNote,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EmptyText(@StringRes text: Int) {
    Text(
        text = stringResource(text),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 8.dp),
    )
}
