package au.com.uulabs.uudownloader.ui

import android.text.format.DateUtils
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import au.com.uulabs.uudownloader.UuViewModel
import au.com.uulabs.uudownloader.core.Quality
import au.com.uulabs.uudownloader.download.DownloadItem
import au.com.uulabs.uudownloader.download.DownloadState

@Composable
fun HomeScreen(
    vm: UuViewModel,
    items: List<DownloadItem>,
    onOpenSettings: () -> Unit,
    onChooseFolder: () -> Unit,
    onOpenFile: (String) -> Unit,
) {
    val running = items.firstOrNull { it.state.isRunning }
    val finished = items.filter { it.state.isTerminal }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        Header(onOpenSettings)

        Column(Modifier.padding(horizontal = 20.dp)) {
            Spacer(Modifier.height(6.dp))
            DestinationCard(vm, onChooseFolder)
            Spacer(Modifier.height(18.dp))
            LinkField(vm)
            Spacer(Modifier.height(16.dp))

            QualitySection(vm)

            Spacer(Modifier.height(20.dp))
            UuButton(
                label = buttonLabel(items),
                enabled = vm.canDownload,
                onClick = {
                    if (vm.startDownload()) vm.clearUrl()
                },
            )

            vm.notice?.let { notice ->
                Spacer(Modifier.height(10.dp))
                Text(
                    text = notice,
                    style = MaterialTheme.typography.bodySmall,
                    color = Uu.Danger,
                )
            }

            AnimatedVisibility(
                visible = running != null,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                Column {
                    Spacer(Modifier.height(26.dp))
                    Eyebrow("In progress")
                    Spacer(Modifier.height(10.dp))
                    running?.let { ActiveCard(it, onCancel = { vm.cancel(it.id) }) }
                }
            }

            Spacer(Modifier.height(28.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Eyebrow("Recent")
                Spacer(Modifier.weight(1f))
                if (finished.isNotEmpty()) {
                    Text(
                        text = "Clear",
                        style = MaterialTheme.typography.bodySmall,
                        color = Uu.Faint,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { vm.clearFinished() }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
            Spacer(Modifier.height(10.dp))

            if (finished.isEmpty()) {
                EmptyRecent()
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    finished.take(8).forEach { item ->
                        RecentRow(item, onOpenFile)
                    }
                }
            }
            Spacer(Modifier.height(40.dp))
        }
    }
}

private fun buttonLabel(items: List<DownloadItem>): String =
    if (items.any { it.state.isRunning }) "Downloading…" else "Download"

@Composable
private fun Header(onOpenSettings: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Eyebrow("UU Labs", color = Uu.Faint)
            Spacer(Modifier.height(6.dp))
            Text(
                text = "UU Downloader",
                style = MaterialTheme.typography.displaySmall,
                fontSize = 26.sp,
                color = Uu.Text,
            )
        }
        Spacer(Modifier.weight(1f))
        Box(
            Modifier
                .size(42.dp)
                .clip(CircleShape)
                .border(1.dp, Uu.Hairline, CircleShape)
                .clickable(onClick = onOpenSettings),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Outlined.Settings,
                contentDescription = "Settings",
                tint = Uu.Muted,
                modifier = Modifier.size(19.dp),
            )
        }
    }
}

@Composable
private fun DestinationCard(vm: UuViewModel, onChooseFolder: () -> Unit) {
    Panel(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 8.dp, top = 13.dp, bottom = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(Uu.Text.copy(alpha = 0.07f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.FolderOpen,
                    contentDescription = null,
                    tint = Uu.Text.copy(alpha = 0.85f),
                    modifier = Modifier.size(17.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Eyebrow("Save to")
                Spacer(Modifier.height(3.dp))
                Text(
                    text = vm.destination.label,
                    style = MaterialTheme.typography.titleMedium,
                    fontSize = 14.sp,
                    color = Uu.Text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box(
                Modifier
                    .clip(RoundedCornerShape(9.dp))
                    .border(1.dp, Uu.Hairline, RoundedCornerShape(9.dp))
                    .clickable(onClick = onChooseFolder)
                    .padding(horizontal = 12.dp, vertical = 7.dp)
            ) {
                Text(
                    text = "Change",
                    style = MaterialTheme.typography.labelLarge,
                    fontSize = 13.sp,
                    color = Uu.Text.copy(alpha = 0.75f),
                )
            }
        }
    }
}

@Composable
private fun LinkField(vm: UuViewModel) {
    val clipboard = LocalClipboardManager.current
    var focused by remember { mutableStateOf(false) }
    val borderColor = when {
        focused -> Uu.Text.copy(alpha = 0.34f)
        vm.link != null -> Uu.Text.copy(alpha = 0.16f)
        else -> Uu.Hairline
    }

    Panel(Modifier.fillMaxWidth(), border = borderColor) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(58.dp)
                .padding(start = 16.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.Link,
                contentDescription = null,
                tint = if (focused) Uu.Text else Uu.Faint,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(12.dp))
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (vm.urlText.isEmpty()) {
                    Text(
                        text = "Paste a link",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Uu.Faint,
                    )
                }
                BasicTextField(
                    value = vm.urlText,
                    onValueChange = vm::onUrlChanged,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = Uu.Text),
                    cursorBrush = SolidColor(Uu.Text),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Done,
                        // A URL field that capitalises and autocorrects is a URL field that
                        // quietly rewrites the link: "sample-5s.mp4" became "sample-5s.MP4".
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { focused = it.isFocused },
                )
            }
            if (vm.urlText.isNotEmpty()) {
                IconButton(onClick = vm::clearUrl) {
                    Icon(
                        Icons.Outlined.Close,
                        contentDescription = "Clear",
                        tint = Uu.Faint,
                        modifier = Modifier.size(17.dp),
                    )
                }
            } else {
                IconButton(onClick = {
                    clipboard.getText()?.text?.let(vm::onUrlChanged)
                }) {
                    Icon(
                        Icons.Outlined.ContentPaste,
                        contentDescription = "Paste",
                        tint = Uu.Muted,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }

    val status = statusLine(vm)
    AnimatedVisibility(visible = status != null) {
        Row(Modifier.padding(top = 10.dp, start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            vm.link?.let { link ->
                PlatformBadge(link.platform, size = 22)
                Spacer(Modifier.width(8.dp))
            }
            Text(
                text = status.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = if (vm.link == null) Uu.Danger else Uu.Muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun statusLine(vm: UuViewModel): String? {
    val link = vm.link
    if (link == null) {
        return if (vm.urlText.isBlank()) null else "That doesn't look like a link"
    }
    val info = vm.sourceInfo
    return info?.title ?: link.platform.label
}

@Composable
private fun QualitySection(vm: UuViewModel) {
    val options = vm.availableQualities()
    val height = vm.sourceInfo?.maxHeight
    Column {
        Eyebrow("Quality")
        Spacer(Modifier.height(9.dp))
        QualityPills(
            options = options,
            selected = vm.quality,
            onSelect = vm::selectQuality,
        )
        Spacer(Modifier.height(8.dp))
        MutedLine(
            when {
                height != null && height > 720 -> "This one goes up to ${height}p. HQ is worth it."
                height != null -> "This one tops out at ${height}p, so Normal is the same file."
                else -> "Normal keeps it small. HQ saves the best the site offers."
            },
            color = Uu.Faint,
        )
    }
}

@Composable
private fun ActiveCard(item: DownloadItem, onCancel: () -> Unit) {
    Panel(Modifier.fillMaxWidth(), border = Uu.Text.copy(alpha = 0.18f)) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PlatformBadge(item.platform)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = item.title ?: "${item.platform.label} video",
                        style = MaterialTheme.typography.titleMedium,
                        fontSize = 14.5.sp,
                        color = Uu.Text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(3.dp))
                    MutedLine(
                        when (item.state) {
                            DownloadState.PREPARING -> "Getting ready"
                            DownloadState.SAVING -> "Saving to your folder"
                            else -> item.detail ?: "Downloading"
                        },
                        color = Uu.Muted,
                    )
                }
                IconButton(onClick = onCancel, modifier = Modifier.size(34.dp)) {
                    Icon(
                        Icons.Outlined.Close,
                        contentDescription = "Cancel",
                        tint = Uu.Muted,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            ProgressLane(item.progress)
            Spacer(Modifier.height(9.dp))
            Row {
                MutedLine(
                    item.progress?.let { "${(it * 100).toInt()}%" } ?: "Working",
                    color = Uu.Text.copy(alpha = 0.9f),
                )
                Spacer(Modifier.weight(1f))
                MutedLine(item.quality.label, color = Uu.Faint)
            }
        }
    }
}

@Composable
private fun RecentRow(item: DownloadItem, onOpenFile: (String) -> Unit) {
    val done = item.state == DownloadState.DONE
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .then(
                if (done && item.savedUri != null) {
                    Modifier.clickable { item.savedUri?.let(onOpenFile) }
                } else {
                    Modifier
                }
            )
            .padding(vertical = 9.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlatformBadge(item.platform, size = 30)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = item.displayName,
                style = MaterialTheme.typography.titleMedium,
                fontSize = 14.sp,
                fontWeight = FontWeight.Normal,
                color = if (done) Uu.Text else Uu.Muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            MutedLine(subtitle(item), color = if (done) Uu.Faint else Uu.Danger)
        }
        if (done) {
            Icon(
                Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = null,
                tint = Uu.Faint,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

private fun subtitle(item: DownloadItem): String {
    val when_ = DateUtils.getRelativeTimeSpanString(
        item.createdAt,
        System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS,
    ).toString()
    return when (item.state) {
        DownloadState.DONE -> "Saved · ${item.platform.label} · $when_"
        DownloadState.CANCELLED -> "Cancelled"
        else -> item.error ?: "Failed"
    }
}

@Composable
private fun EmptyRecent() {
    Panel(Modifier.fillMaxWidth(), background = Uu.InkLift) {
        Column(Modifier.padding(18.dp)) {
            Text(
                text = "Nothing saved yet",
                style = MaterialTheme.typography.titleMedium,
                fontSize = 14.sp,
                color = Uu.Muted,
            )
            Spacer(Modifier.height(5.dp))
            MutedLine("Paste a link above, or share one straight from the app you're watching in.", color = Uu.Faint)
        }
    }
}
