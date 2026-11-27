package com.vakarux.instadownload

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.text.format.DateUtils
import android.text.format.Formatter
import android.widget.Toast
import android.widget.VideoView
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SliderState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Tab
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.vakarux.instadownload.ui.AppIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToLong

private enum class DownloadKind(val labelRes: Int) {
    ALL(R.string.downloads_all),
    REELS(R.string.downloads_reels),
    POSTS(R.string.downloads_posts),
    STORIES(R.string.downloads_stories)
}

private val DownloadRecord.kind: DownloadKind
    get() = when {
        meta?.url?.contains("/stories/") == true -> DownloadKind.STORIES
        meta?.url?.contains("/reel/") == true || meta?.url?.contains("/reels/") == true -> DownloadKind.REELS
        else -> DownloadKind.POSTS
    }

/**
 * The cleaned Instagram link for a downloaded record, or null when the record has no post URL.
 * Used by the multi-select copy/share/export actions.
 */
private val DownloadRecord.link: String?
    get() = meta?.url?.takeIf { it.isNotBlank() }?.let { InstagramDownloader.cleanInstagramUrl(it) }

private enum class LibraryTab(val labelRes: Int) {
    ALL(R.string.downloads_all),
    REELS(R.string.downloads_reels),
    POSTS(R.string.downloads_posts),
    STORIES(R.string.downloads_stories),
    FAILED(R.string.downloads_failed),
    HISTORY(R.string.history_title),
}

@Composable
fun DownloadsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var records by remember { mutableStateOf(DownloadHistory.load(context)) }
    var failed by remember { mutableStateOf(FailedDownloads.load(context)) }
    var links by remember { mutableStateOf(LinkHistory.load(context)) }
    var tab by remember { mutableStateOf(LibraryTab.ALL) }
    var previewRecord by remember { mutableStateOf<DownloadRecord?>(null) }
    var fullscreen by remember { mutableStateOf(false) }
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<String>()) }

    fun reload() {
        records = DownloadHistory.load(context)
        failed = FailedDownloads.load(context)
        links = LinkHistory.load(context)
    }

    fun exitSelection() {
        selecting = false
        selected = emptySet()
    }

    Dialog(
        onDismissRequest = {
            if (fullscreen) fullscreen = false
            else if (previewRecord != null) previewRecord = null
            else if (selecting) exitSelection()
            else onDismiss()
        },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        val view = LocalView.current
        LaunchedEffect(fullscreen) {
            val window = (view.parent as? DialogWindowProvider)?.window ?: return@LaunchedEffect
            WindowCompat.getInsetsController(window, view).run {
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                if (fullscreen) hide(WindowInsetsCompat.Type.systemBars())
                else show(WindowInsetsCompat.Type.systemBars())
            }
        }
        Surface(
            modifier = Modifier.fillMaxSize()
                .then(if (fullscreen) Modifier else Modifier.statusBarsPadding().navigationBarsPadding()),
            color = if (fullscreen) Color.Black else MaterialTheme.colorScheme.background
        ) {
            Column {
                if (!fullscreen) {
                    if (selecting) {
                        SelectionHeader(
                            count = selected.size,
                            onDone = { exitSelection() }
                        )
                    } else {
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                stringResource(if (previewRecord == null) R.string.downloads_title else R.string.preview_label),
                                style = MaterialTheme.typography.headlineSmall,
                                modifier = Modifier.weight(1f).padding(start = 20.dp, top = 16.dp, bottom = 12.dp)
                            )
                            if (previewRecord == null) {
                                IconButton(onClick = { reload() }, modifier = Modifier.padding(top = 8.dp)) {
                                    Icon(
                                        imageVector = AppIcons.Refresh,
                                        contentDescription = stringResource(R.string.refresh_downloads_label)
                                    )
                                }
                            }
                            TextButton(onClick = {
                                if (previewRecord == null) onDismiss() else previewRecord = null
                            }, modifier = Modifier.padding(top = 8.dp, end = 12.dp)) {
                                Text(stringResource(if (previewRecord == null) R.string.close_label else R.string.back_label))
                            }
                        }
                    }
                }
                val record = previewRecord
                if (record != null) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .then(if (fullscreen) Modifier else Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp))
                    ) { MediaPreview(record, fullscreen) { fullscreen = it } }
                } else {
                    ScrollableTabRow(
                        selectedTabIndex = tab.ordinal,
                        edgePadding = 12.dp
                    ) {
                        LibraryTab.entries.forEach { entry ->
                            val count = when (entry) {
                                LibraryTab.FAILED -> failed.size
                                LibraryTab.HISTORY -> links.size
                                else -> null
                            }
                            Tab(
                                selected = tab == entry,
                                onClick = {
                                    if (tab != entry) exitSelection()
                                    tab = entry
                                },
                                text = {
                                    val label = if (count != null && count > 0) {
                                        "${stringResource(entry.labelRes)} ($count)"
                                    } else {
                                        stringResource(entry.labelRes)
                                    }
                                    Text(
                                        label,
                                        color = if (entry == LibraryTab.FAILED) MaterialTheme.colorScheme.error
                                        else Color.Unspecified
                                    )
                                }
                            )
                        }
                    }
                    when (tab) {
                        LibraryTab.FAILED -> FailedDownloadsList(
                            failed = failed,
                            selecting = selecting,
                            selected = selected,
                            onToggleSelect = { selected = selected.toggle(it.url) },
                            onStartSelection = { url -> selecting = true; selected = setOf(url) },
                            onRetry = { item ->
                                // Retrying does not clear the failed entry — links stay in the
                                // list until the user removes them or taps Clear failed.
                                QuickDownloadService.startDownload(context, item.url)
                                Toast.makeText(context, R.string.retry_started, Toast.LENGTH_SHORT).show()
                            },
                            onRetryAll = {
                                failed.forEach { QuickDownloadService.startDownload(context, it.url) }
                                Toast.makeText(context, R.string.retry_started, Toast.LENGTH_SHORT).show()
                            },
                            onRemove = { item ->
                                FailedDownloads.remove(context, item.url)
                                reload()
                                Toast.makeText(context, R.string.failed_removed, Toast.LENGTH_SHORT).show()
                            },
                            onClear = {
                                FailedDownloads.clear(context)
                                reload()
                                Toast.makeText(context, R.string.failed_cleared, Toast.LENGTH_SHORT).show()
                            }
                        )

                        LibraryTab.HISTORY -> LinkHistoryList(
                            links = links,
                            selecting = selecting,
                            selected = selected,
                            onToggleSelect = { selected = selected.toggle(it) },
                            onStartSelection = { url -> selecting = true; selected = setOf(url) },
                            onRemove = { url ->
                                LinkHistory.remove(context, url)
                                selected = selected - url
                                reload()
                                Toast.makeText(context, R.string.history_removed, Toast.LENGTH_SHORT).show()
                            },
                            onClear = {
                                LinkHistory.clear(context)
                                exitSelection()
                                reload()
                                Toast.makeText(context, R.string.history_cleared, Toast.LENGTH_SHORT).show()
                            },
                            onDownload = { url -> QuickDownloadService.startDownload(context, url) }
                        )

                        else -> {
                            val visibleRecords = records.filter {
                                tab == LibraryTab.ALL || it.kind.name == tab.name
                            }
                            DownloadsLibrary(
                                records = records,
                                visible = visibleRecords,
                                kind = tab,
                                selecting = selecting,
                                selected = selected,
                                onStartSelection = { link -> selecting = true; selected = setOf(link) },
                                onToggleSelect = { selected = selected.toggle(it) },
                                onPreview = { previewRecord = it },
                                onDeleted = { rec ->
                                    DownloadHistory.remove(context, rec)
                                    records = records.filterNot { it.uri == rec.uri }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun Set<String>.toggle(value: String): Set<String> =
    if (value in this) this - value else this + value

/**
 * The header shown while in multi-select mode: how many items are checked plus the batch actions
 * (select all / none, copy, share and save-as-txt) and a Done button to leave selection mode.
 */
@Composable
private fun SelectionHeader(count: Int, onDone: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            stringResource(R.string.selected_count_format, count),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = onDone) { Text(stringResource(R.string.select_done_label)) }
    }
}

/**
 * A floating action bar with the batch link actions. Shown for the Downloads, Failed and Links
 * tabs whenever selection mode is on.
 *
 * @param selected the currently checked URLs (cleaned links) in display order
 * @param allLinks every link currently visible on the tab, used by select-all/none
 * @param onSelectAll replaces the selection with [allLinks]
 * @param onSelectNone clears the selection
 * @param onExport persists the selection to a .txt file and opens the share sheet
 */
@Composable
private fun SelectionBar(
    selected: List<String>,
    allLinks: List<String>,
    onSelectAll: () -> Unit,
    onSelectNone: () -> Unit,
    onExport: (List<String>) -> Unit
) {
    val context = LocalContext.current
    if (selected.isEmpty()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                stringResource(R.string.no_links_selected),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onSelectAll) { Text(stringResource(R.string.select_all_links_label)) }
        }
        return
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextButton(onClick = onSelectNone) { Text(stringResource(R.string.clear_selection_label)) }
        TextButton(onClick = onSelectAll) { Text(stringResource(R.string.select_all_links_label)) }
        Spacer(Modifier.weight(1f))
        IconButton(onClick = { copyLinks(context, selected) }) {
            Icon(
                imageVector = AppIcons.ContentCopy,
                contentDescription = stringResource(R.string.copy_links_label)
            )
        }
        IconButton(onClick = { shareLinks(context, selected) }) {
            Icon(
                imageVector = AppIcons.Share,
                contentDescription = stringResource(R.string.share_links_label)
            )
        }
        IconButton(onClick = { onExport(selected) }) {
            Icon(
                imageVector = AppIcons.Save,
                contentDescription = stringResource(R.string.export_links_label)
            )
        }
    }
}

/**
 * Lists links whose download failed, so the user can retry them later. Each row can be retried,
 * removed, or selected (long-press) for batch copy/share/export. Entries are kept until the user
 * removes them or taps Clear failed.
 */
@Composable
private fun ColumnScope.FailedDownloadsList(
    failed: List<FailedDownload>,
    selecting: Boolean,
    selected: Set<String>,
    onToggleSelect: (FailedDownload) -> Unit,
    onStartSelection: (String) -> Unit,
    onRetry: (FailedDownload) -> Unit,
    onRetryAll: () -> Unit,
    onRemove: (FailedDownload) -> Unit,
    onClear: () -> Unit
) {
    val context = LocalContext.current
    val linkClipboardLabel = stringResource(R.string.failed_link_clipboard_label)
    Column(modifier = Modifier.fillMaxWidth().weight(1f)) {
        if (!selecting) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(R.string.failed_downloads_empty_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onRetryAll, enabled = failed.isNotEmpty()) {
                    Text(stringResource(R.string.retry_all_label))
                }
                TextButton(onClick = onClear, enabled = failed.isNotEmpty()) {
                    Text(stringResource(R.string.clear_failed_label))
                }
            }
        }
        if (failed.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    stringResource(R.string.failed_downloads_empty),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(failed, key = { it.url }) { item ->
                    SelectableCard(
                        selected = item.url in selected,
                        selecting = selecting,
                        onClick = { if (selecting) onToggleSelect(item) },
                        onLongClick = { if (selecting) onToggleSelect(item) else onStartSelection(item.url) }
                    ) {
                        Text(
                            item.url,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = if (selecting) Modifier else Modifier.combinedClickable(
                                onClick = {},
                                onLongClick = {
                                    copyDownloadText(
                                        context = context,
                                        label = linkClipboardLabel,
                                        text = item.url,
                                        confirmation = R.string.failed_link_copied
                                    )
                                }
                            )
                        )
                        Text(
                            stringResource(
                                R.string.failed_when_format,
                                DateUtils.getRelativeTimeSpanString(
                                    item.failedAt,
                                    System.currentTimeMillis(),
                                    DateUtils.MINUTE_IN_MILLIS
                                ).toString()
                            ) + " · " + stringResource(
                                R.string.failed_attempts_format,
                                item.attempts
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                        if (item.error.isNotBlank()) {
                            Text(
                                item.error,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                        if (!selecting) {
                            Row(
                                modifier = Modifier.padding(top = 6.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                TextButton(onClick = { onRetry(item) }) {
                                    Text(stringResource(R.string.retry_download_label))
                                }
                                TextButton(onClick = { onRemove(item) }) {
                                    Text(stringResource(R.string.remove_label))
                                }
                            }
                        }
                    }
                }
            }
            if (selecting) {
                SelectionBar(
                    selected = failed.map { it.url }.filter { it in selected },
                    allLinks = failed.map { it.url },
                    onSelectAll = { failed.forEach { if (it.url !in selected) onToggleSelect(it) } },
                    onSelectNone = { failed.forEach { if (it.url in selected) onToggleSelect(it) } },
                    onExport = { exportLinks(context, it) }
                )
            }
        }
    }
}

/**
 * The persistent link history: every Instagram link pasted or shared into the app, including
 * links that later failed. Entries are only removed by an explicit action here.
 */
@Composable
private fun ColumnScope.LinkHistoryList(
    links: List<LinkRecord>,
    selecting: Boolean,
    selected: Set<String>,
    onToggleSelect: (String) -> Unit,
    onStartSelection: (String) -> Unit,
    onRemove: (String) -> Unit,
    onClear: () -> Unit,
    onDownload: (String) -> Unit
) {
    val context = LocalContext.current
    var confirmClear by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth().weight(1f)) {
        if (!selecting) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(R.string.history_empty_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { confirmClear = true }, enabled = links.isNotEmpty()) {
                    Text(stringResource(R.string.history_clear_label))
                }
            }
        }
        if (links.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                    Icon(
                        imageVector = AppIcons.History,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(56.dp)
                    )
                    Text(
                        stringResource(R.string.history_empty),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(top = 16.dp)
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(links, key = { it.url }) { item ->
                    SelectableCard(
                        selected = item.url in selected,
                        selecting = selecting,
                        onClick = { if (selecting) onToggleSelect(item.url) },
                        onLongClick = {
                            if (selecting) onToggleSelect(item.url) else onStartSelection(item.url)
                        }
                    ) {
                        Text(
                            item.url,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            stringResource(
                                R.string.history_seen_format,
                                DateUtils.getRelativeTimeSpanString(
                                    item.lastSeen,
                                    System.currentTimeMillis(),
                                    DateUtils.MINUTE_IN_MILLIS
                                ).toString(),
                                item.count
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                        if (!selecting) {
                            Row(
                                modifier = Modifier.padding(top = 6.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                TextButton(onClick = { onDownload(item.url) }) {
                                    Text(stringResource(R.string.download_label))
                                }
                                TextButton(onClick = { onRemove(item.url) }) {
                                    Text(stringResource(R.string.remove_label))
                                }
                            }
                        }
                    }
                }
            }
            if (selecting) {
                SelectionBar(
                    selected = links.map { it.url }.filter { it in selected },
                    allLinks = links.map { it.url },
                    onSelectAll = { links.forEach { onToggleSelect(it.url) } },
                    onSelectNone = { links.forEach { onToggleSelect(it.url) } },
                    onExport = { exportLinks(context, it) }
                )
            }
        }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.history_clear_confirm_title)) },
            text = { Text(stringResource(R.string.history_clear_confirm_text)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    onClear()
                }) { Text(stringResource(R.string.clear_label)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) {
                    Text(stringResource(R.string.cancel_label))
                }
            }
        )
    }
}

/** The saved-file library for the All / Reels / Posts / Stories tabs. */
@Composable
private fun ColumnScope.DownloadsLibrary(
    records: List<DownloadRecord>,
    visible: List<DownloadRecord>,
    kind: LibraryTab,
    selecting: Boolean,
    selected: Set<String>,
    onStartSelection: (String) -> Unit,
    onToggleSelect: (String) -> Unit,
    onPreview: (DownloadRecord) -> Unit,
    onDeleted: (DownloadRecord) -> Unit
) {
    val context = LocalContext.current
    if (visible.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(32.dp)
            ) {
                Icon(
                    imageVector = AppIcons.VideoLibrary,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(56.dp)
                )
                Text(
                    if (records.isEmpty()) stringResource(R.string.downloads_empty)
                    else stringResource(R.string.downloads_empty_kind, stringResource(kind.labelRes)),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(top = 16.dp)
                )
                if (records.isEmpty()) {
                    Text(
                        stringResource(R.string.downloads_empty_hint),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxWidth().weight(1f),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(visible, key = { it.uri }) { record ->
            val link = record.link
            DownloadRow(
                record = record,
                selecting = selecting,
                checked = link != null && link in selected,
                onPreview = onPreview,
                onLongPress = { link?.let { onStartSelection(it) } },
                onToggleCheck = { link?.let { onToggleSelect(it) } },
                onDeleted = { onDeleted(record) }
            )
        }
    }
    if (selecting) {
        val links = visible.mapNotNull { it.link }.distinct()
        SelectionBar(
            selected = links.filter { it in selected },
            allLinks = links,
            onSelectAll = { links.forEach { onToggleSelect(it) } },
            onSelectNone = { links.forEach { onToggleSelect(it) } },
            onExport = { exportLinks(context, it) }
        )
    }
}

/**
 * Wraps a card in the common selection look: a leading check indicator while [selecting], plus
 * tap-to-toggle and long-press-to-start-selection handling. [onLongClick] keeps the row's own
 * long-press action (e.g. copy) when not in selection mode.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SelectableCard(
    selected: Boolean,
    selecting: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.surfaceVariant
        ),
        modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (selecting) {
                Icon(
                    imageVector = if (selected) AppIcons.CheckCircle else AppIcons.RadioButtonUnchecked,
                    contentDescription = null,
                    tint = if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 12.dp).size(22.dp)
                )
            }
            Column(modifier = Modifier.padding(12.dp).weight(1f), content = content)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DownloadRow(
    record: DownloadRecord,
    selecting: Boolean,
    checked: Boolean,
    onPreview: (DownloadRecord) -> Unit,
    onLongPress: () -> Unit,
    onToggleCheck: () -> Unit,
    onDeleted: () -> Unit
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val handleClipboardLabel = stringResource(R.string.handle_clipboard_label)
    val captionClipboardLabel = stringResource(R.string.caption_clipboard_label)
    val postLinkClipboardLabel = stringResource(R.string.post_link_clipboard_label)
    var captionExpanded by remember(record.uri) { mutableStateOf(false) }
    var captionHasOverflow by remember(record.uri) { mutableStateOf(false) }
    val captionScrollState = rememberScrollState()
    val exists by produceState<Boolean?>(null, record.uri) {
        value = withContext(Dispatchers.IO) { DownloadedFiles.exists(context, record) }
    }
    val size by produceState(-1L, record.uri) {
        value = withContext(Dispatchers.IO) { DownloadedFiles.sizeBytes(context, record) }
    }
    val meta = record.meta
    val username = meta?.username?.takeIf { it.isNotBlank() }
    val caption = meta?.caption?.takeIf { it.isNotBlank() }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (checked) MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.surfaceVariant
        ),
        modifier = Modifier.combinedClickable(
            onClick = { if (selecting) onToggleCheck() },
            onLongClick = { if (selecting) onToggleCheck() else onLongPress() }
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                if (selecting) {
                    Icon(
                        imageVector = if (checked) AppIcons.CheckCircle else AppIcons.RadioButtonUnchecked,
                        contentDescription = null,
                        tint = if (checked) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp).size(22.dp)
                    )
                    Spacer(Modifier.size(8.dp))
                }
                DownloadThumbnail(
                    record = record,
                    exists = exists,
                    onClick = { if (selecting) onToggleCheck() else onPreview(record) }
                )
                Spacer(Modifier.size(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        username?.let { "@$it" } ?: stringResource(R.string.unknown_account),
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.then(
                            if (username == null || selecting) Modifier else Modifier.combinedClickable(
                                onClick = {},
                                onLongClick = {
                                    copyDownloadText(
                                        context = context,
                                        label = handleClipboardLabel,
                                        text = "@$username",
                                        confirmation = R.string.handle_copied
                                    )
                                }
                            )
                        )
                    )
                    Text(
                        caption ?: stringResource(R.string.no_caption),
                        maxLines = if (captionExpanded) Int.MAX_VALUE else 3,
                        overflow = if (captionExpanded) TextOverflow.Clip else TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        onTextLayout = { captionHasOverflow = it.hasVisualOverflow },
                        modifier = Modifier
                            .padding(top = 2.dp)
                            .then(
                                if (captionExpanded) {
                                    Modifier.heightIn(max = 160.dp).verticalScroll(captionScrollState)
                                } else {
                                    Modifier
                                }
                            )
                            .then(
                                if (caption == null || selecting) Modifier else Modifier.combinedClickable(
                                    onClick = { captionExpanded = !captionExpanded },
                                    onLongClick = {
                                        copyDownloadText(
                                            context = context,
                                            label = captionClipboardLabel,
                                            text = caption,
                                            confirmation = R.string.caption_copied
                                        )
                                    }
                                )
                            )
                    )
                    if (!selecting && caption != null && (captionHasOverflow || captionExpanded)) {
                        TextButton(
                            onClick = { captionExpanded = !captionExpanded },
                            contentPadding = PaddingValues(0.dp),
                            modifier = Modifier.heightIn(min = 28.dp)
                        ) {
                            Text(
                                stringResource(
                                    if (captionExpanded) R.string.show_less_label else R.string.show_more_label
                                ),
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }
                    val details = listOfNotNull(
                        meta?.song,
                        meta?.takenAtSec?.takeIf { it > 0 }
                            ?.let { stringResource(R.string.posted_date_format, formatDate(it * 1000)) },
                        record.takeIf { it.width > 0 && it.height > 0 }
                            ?.let { "${it.width}×${it.height}" },
                        record.durationSec.takeIf { it > 0 }
                            ?.let { DateUtils.formatElapsedTime(it.roundToLong()) },
                        size.takeIf { it > 0 }?.let { Formatter.formatShortFileSize(context, it) },
                        stringResource(R.string.downloaded_date_format, formatDate(record.downloadedAt))
                    )
                    Text(
                        details.joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    meta?.url?.let { rawPostUrl ->
                        // Tracking query strings (e.g. ?igsh=...) are stripped so copied/opened
                        // links are short, clean and do not carry the sharer's identifier.
                        val postUrl = InstagramDownloader.cleanInstagramUrl(rawPostUrl)
                        Text(
                            postUrl,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .padding(top = 3.dp)
                                .combinedClickable(
                                    onClick = { if (!selecting) uriHandler.openUri(postUrl) },
                                    onLongClick = {
                                        if (!selecting) {
                                            copyDownloadText(
                                                context = context,
                                                label = postLinkClipboardLabel,
                                                text = postUrl,
                                                confirmation = R.string.post_link_copied
                                            )
                                        }
                                    }
                                )
                        )
                    } ?: Text(
                        stringResource(R.string.no_post_link),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 3.dp)
                    )
                }
            }
            if (exists == false) {
                Text(
                    stringResource(R.string.file_missing),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
            if (!selecting) {
                Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    TextButton(
                        onClick = { onPreview(record) },
                        enabled = exists != false,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 4.dp)
                    ) { Text(stringResource(R.string.preview_label), maxLines = 1) }
                    TextButton(
                        onClick = { startFileAction(context) { DownloadedFiles.openIntent(context, record) } },
                        enabled = exists != false,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 4.dp)
                    ) { Text(stringResource(R.string.open_label), maxLines = 1) }
                    TextButton(
                        onClick = { startFileAction(context) { DownloadedFiles.shareIntent(context, record) } },
                        enabled = exists != false,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 4.dp)
                    ) { Text(stringResource(R.string.share_label), maxLines = 1) }
                    TextButton(
                        onClick = {
                            if (DownloadedFiles.delete(context, record)) onDeleted()
                            else Toast.makeText(context, R.string.delete_failed, Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 4.dp)
                    ) { Text(stringResource(R.string.delete_label), maxLines = 1) }
                }
            }
        }
    }
}

@Composable
private fun DownloadThumbnail(record: DownloadRecord, exists: Boolean?, onClick: () -> Unit) {
    val context = LocalContext.current
    val bitmap by produceState<ImageBitmap?>(null, record.uri, exists) {
        if (exists != false) {
            value = withContext(Dispatchers.IO) {
                DownloadedFiles.previewBitmap(context, record, 320)?.asImageBitmap()
            }
        }
    }
    Box(
        modifier = Modifier
            .size(width = 104.dp, height = 132.dp)
            .background(Color.Black.copy(alpha = 0.18f))
            .clickable(enabled = exists != false, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        bitmap?.let {
            Image(
                bitmap = it,
                contentDescription = stringResource(
                    if (record.mimeType.startsWith("video/")) {
                        R.string.video_preview_description
                    } else {
                        R.string.image_preview_description
                    }
                ),
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().height(132.dp)
            )
        } ?: if (exists == null) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp))
        } else {
            Icon(
                imageVector = if (record.mimeType.startsWith("video/")) AppIcons.Movie else AppIcons.Image,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.7f),
                modifier = Modifier.size(32.dp)
            )
        }
        if (record.mimeType.startsWith("video/") && bitmap != null) {
            Icon(
                imageVector = AppIcons.PlayArrow,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(32.dp)
            )
        }
    }
}

@Composable
private fun ColumnScope.MediaFrame(fullscreen: Boolean = false, content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier = Modifier
            .then(
                if (fullscreen) Modifier.weight(1f).fillMaxWidth()
                else Modifier
                    .weight(1f, fill = false)
                    .padding(horizontal = 24.dp, vertical = 8.dp)
                    .aspectRatio(9f / 16f, matchHeightConstraintsFirst = true)
            )
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) { content() }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MediaPreview(record: DownloadRecord, fullscreen: Boolean, onFullscreenChange: (Boolean) -> Unit) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val handleClipboardLabel = stringResource(R.string.handle_clipboard_label)
    val captionClipboardLabel = stringResource(R.string.caption_clipboard_label)
    val postLinkClipboardLabel = stringResource(R.string.post_link_clipboard_label)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        if (record.mimeType.startsWith("video/")) VideoPreview(record, fullscreen, onFullscreenChange)
        else ImagePreview(record)
        if (fullscreen) return@Column
        val meta = record.meta
        val username = meta?.username?.takeIf { it.isNotBlank() }
        val caption = meta?.caption?.takeIf { it.isNotBlank() }
        username?.let { handle ->
            Text(
                "@$handle",
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .padding(top = 12.dp)
                    .combinedClickable(
                        onClick = {},
                        onLongClick = {
                            copyDownloadText(
                                context = context,
                                label = handleClipboardLabel,
                                text = "@$handle",
                                confirmation = R.string.handle_copied
                            )
                        }
                    )
            )
        }
        caption?.let { text ->
            Text(
                text,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 180.dp)
                    .verticalScroll(rememberScrollState())
                    .combinedClickable(
                        onClick = {},
                        onLongClick = {
                            copyDownloadText(
                                context = context,
                                label = captionClipboardLabel,
                                text = text,
                                confirmation = R.string.caption_copied
                            )
                        }
                    )
                    .padding(top = 4.dp)
            )
        }
        meta?.url?.let { rawPostUrl ->
            val postUrl = InstagramDownloader.cleanInstagramUrl(rawPostUrl)
            Text(
                postUrl,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
                    .combinedClickable(
                        onClick = { uriHandler.openUri(postUrl) },
                        onLongClick = {
                            copyDownloadText(
                                context = context,
                                label = postLinkClipboardLabel,
                                text = postUrl,
                                confirmation = R.string.post_link_copied
                            )
                        }
                    )
            )
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = {
                startFileAction(context) { DownloadedFiles.shareIntent(context, record) }
            }) { Text(stringResource(R.string.share_label)) }
        }
    }
}

@Composable
private fun ColumnScope.ImagePreview(record: DownloadRecord) {
    val context = LocalContext.current
    val result by produceState<Result<ImageBitmap>?>(null, record.uri) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                DownloadedFiles.previewBitmap(context, record, 1200)?.asImageBitmap()
                    ?: error("Preview unavailable")
            }
        }
    }

    MediaFrame {
        when {
            result == null -> CircularProgressIndicator(color = Color.White)
            result?.isSuccess == true -> ZoomableBitmap(result!!.getOrThrow())
            else -> Text(
                stringResource(R.string.preview_unavailable),
                color = Color.White,
                modifier = Modifier.padding(16.dp)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ColumnScope.VideoPreview(
    record: DownloadRecord,
    fullscreen: Boolean,
    onFullscreenChange: (Boolean) -> Unit
) {
    val context = LocalContext.current
    var failed by remember(record.uri) { mutableStateOf(false) }
    var playing by remember(record.uri) { mutableStateOf(false) }
    var durationMs by remember(record.uri) { mutableStateOf(0) }
    var positionMs by remember(record.uri) { mutableFloatStateOf(0f) }
    var seeking by remember(record.uri) { mutableStateOf(false) }
    var controlsVisible by remember(record.uri) { mutableStateOf(true) }
    val videoView = remember(record.uri) {
        VideoView(context).apply {
            setVideoURI(Uri.parse(record.uri))
            setOnPreparedListener {
                durationMs = it.duration
                it.start()
                playing = true
            }
            setOnCompletionListener { playing = false }
            setOnErrorListener { _, _, _ ->
                failed = true
                true
            }
        }
    }
    val togglePlay = {
        if (playing) videoView.pause() else videoView.start()
        playing = !playing
    }
    DisposableEffect(videoView) {
        onDispose { videoView.stopPlayback() }
    }
    LaunchedEffect(fullscreen) { controlsVisible = true }
    LaunchedEffect(controlsVisible, playing, seeking, fullscreen) {
        if (fullscreen && controlsVisible && playing && !seeking) {
            delay(3000)
            controlsVisible = false
        }
    }
    LaunchedEffect(videoView, playing) {
        while (playing) {
            if (!seeking) positionMs = videoView.currentPosition.toFloat()
            delay(200)
        }
    }

    val controls: @Composable (Modifier, Color) -> Unit = { modifier, textColor ->
        Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
            FilledIconButton(
                onClick = togglePlay,
                shapes = IconButtonDefaults.shapes(),
                modifier = Modifier.size(IconButtonDefaults.mediumContainerSize())
            ) {
                Icon(
                    imageVector = if (playing) AppIcons.Pause else AppIcons.PlayArrow,
                    contentDescription = stringResource(if (playing) R.string.pause_label else R.string.play_label),
                    modifier = Modifier.size(IconButtonDefaults.mediumIconSize)
                )
            }
            Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                val sliderInteraction = remember { MutableInteractionSource() }
                val sliderState = remember(record.uri) { SliderState() }
                if (!seeking && durationMs > 0) sliderState.value = positionMs / durationMs
                Slider(
                    state = sliderState,
                    onValueChange = {
                        seeking = true
                        sliderState.value = it
                        positionMs = it * durationMs
                        videoView.seekTo(positionMs.toInt())
                    },
                    onValueChangeFinished = { seeking = false },
                    enabled = durationMs > 0,
                    interactionSource = sliderInteraction,
                    thumb = { SliderDefaults.Thumb(sliderInteraction) },
                    track = { state ->
                        LinearWavyProgressIndicator(
                            progress = { state.value },
                            amplitude = { if (playing && !seeking) 1f else 0f },
                            trackColor = Color.White.copy(alpha = 0.3f),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                )
                Text(
                    DateUtils.formatElapsedTime((positionMs / 1000).toLong()) + " / " +
                        DateUtils.formatElapsedTime(durationMs / 1000L),
                    style = MaterialTheme.typography.labelMedium,
                    color = textColor
                )
            }
            IconButton(onClick = { onFullscreenChange(!fullscreen) }) {
                Icon(
                    imageVector = if (fullscreen) AppIcons.FullscreenExit else AppIcons.Fullscreen,
                    contentDescription = stringResource(
                        if (fullscreen) R.string.exit_fullscreen_label else R.string.fullscreen_label
                    ),
                    tint = textColor
                )
            }
        }
    }

    MediaFrame(fullscreen) {
        if (failed) {
            Text(stringResource(R.string.preview_unavailable), color = Color.White)
            return@MediaFrame
        }
        AndroidView(factory = { videoView }, modifier = Modifier.fillMaxSize())
        Box(
            Modifier
                .fillMaxSize()
                .clickable(interactionSource = null, indication = null) {
                    if (!fullscreen) togglePlay() else controlsVisible = !controlsVisible
                }
        )
        if (fullscreen) {
            val shown = controlsVisible || !playing
            val controlsAlpha by animateFloatAsState(if (shown) 1f else 0f, label = "controls")
            if (shown || controlsAlpha > 0f) {
                controls(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .alpha(controlsAlpha)
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f))))
                        .padding(start = 12.dp, end = 4.dp, top = 24.dp, bottom = 24.dp),
                    Color.White
                )
            }
        }
    }
    if (!failed && !fullscreen) {
        controls(
            Modifier.fillMaxWidth().padding(top = 4.dp),
            MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** Puts all [urls] on the clipboard as newline-separated plain text. */
private fun copyLinks(context: Context, urls: List<String>) {
    if (urls.isEmpty()) {
        Toast.makeText(context, R.string.no_links_selected, Toast.LENGTH_SHORT).show()
        return
    }
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Instagram links", urls.joinToString("\n")))
    if (AppSettings(context).hapticsEnabled) hapticTick(context)
    Toast.makeText(context, context.getString(R.string.links_copied_format, urls.size), Toast.LENGTH_SHORT).show()
}

/** Opens the share sheet with all [urls] as newline-separated text. */
private fun shareLinks(context: Context, urls: List<String>) {
    if (urls.isEmpty()) {
        Toast.makeText(context, R.string.no_links_selected, Toast.LENGTH_SHORT).show()
        return
    }
    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(android.content.Intent.EXTRA_TEXT, urls.joinToString("\n"))
        putExtra(android.content.Intent.EXTRA_TITLE, context.getString(R.string.links_clipboard_label))
    }
    startFileAction(context) { android.content.Intent.createChooser(intent, context.getString(R.string.share_links_label)) }
}

/**
 * Exports [urls] to a `.txt` file in the app cache and opens the share sheet so the user can save
 * it anywhere (Downloads, Drive, email, chat…). Failures surface a toast instead of crashing.
 */
private fun exportLinks(context: Context, urls: List<String>) {
    if (urls.isEmpty()) {
        Toast.makeText(context, R.string.no_links_selected, Toast.LENGTH_SHORT).show()
        return
    }
    val export = runCatching {
        val dir = java.io.File(context.cacheDir, "exports").apply { mkdirs() }
        val file = java.io.File(dir, context.getString(R.string.export_filename))
        file.writeText(urls.joinToString("\n") + "\n")
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            putExtra(android.content.Intent.EXTRA_TEXT, urls.joinToString("\n"))
            putExtra(android.content.Intent.EXTRA_TITLE, context.getString(R.string.export_filename))
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        android.content.Intent.createChooser(intent, context.getString(R.string.export_links_label))
    }
    export.fold(
        onSuccess = { startFileAction(context) { it } },
        onFailure = { Toast.makeText(context, R.string.export_failed, Toast.LENGTH_SHORT).show() }
    )
}

private fun hapticTick(context: Context) {
    val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }
    if (vibrator.hasVibrator()) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(35, 120))
        } else {
            @Suppress("DEPRECATION") vibrator.vibrate(35)
        }
    }
}

private fun copyDownloadText(
    context: Context,
    label: String,
    text: String,
    @androidx.annotation.StringRes confirmation: Int
) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
    if (AppSettings(context).hapticsEnabled) hapticTick(context)
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(context, confirmation, Toast.LENGTH_SHORT).show()
    }
}

private fun startFileAction(context: android.content.Context, intent: () -> android.content.Intent) {
    try {
        context.startActivity(intent())
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, R.string.no_app_to_open, Toast.LENGTH_SHORT).show()
    } catch (_: Exception) {
        Toast.makeText(context, R.string.file_action_failed, Toast.LENGTH_SHORT).show()
    }
}

private fun formatDate(timeMs: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(timeMs))
