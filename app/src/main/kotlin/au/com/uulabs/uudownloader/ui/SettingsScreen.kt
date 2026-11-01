package au.com.uulabs.uudownloader.ui

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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.painterResource
import au.com.uulabs.uudownloader.BuildConfig
import au.com.uulabs.uudownloader.R
import au.com.uulabs.uudownloader.UpdateBusy
import au.com.uulabs.uudownloader.UuViewModel
import au.com.uulabs.uudownloader.core.Quality
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import au.com.uulabs.uudownloader.data.ByteSize
import au.com.uulabs.uudownloader.data.Destination
import au.com.uulabs.uudownloader.data.LimitMode
import au.com.uulabs.uudownloader.data.StorageLimit

@Composable
fun SettingsScreen(
    vm: UuViewModel,
    onBack: () -> Unit,
    onChooseFolder: () -> Unit,
    onPickCookies: () -> Unit,
    onOpenAbout: () -> Unit,
    onEngineMessage: (String) -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RoundIconButton(Icons.AutoMirrored.Outlined.ArrowBack, "Back", onBack)
            Spacer(Modifier.width(12.dp))
            ScreenTitle("Settings")
        }

        Column(Modifier.padding(horizontal = 20.dp)) {
            Spacer(Modifier.height(8.dp))
            Eyebrow("Where videos go")
            Spacer(Modifier.height(10.dp))
            Panel(Modifier.fillMaxWidth()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onChooseFolder)
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(Uu.Text.copy(alpha = 0.07f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painterResource(R.drawable.ic_folder_open),
                            contentDescription = null,
                            tint = Uu.Text.copy(alpha = 0.85f),
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    Spacer(Modifier.width(13.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = vm.destination.label,
                            style = MaterialTheme.typography.titleMedium,
                            fontSize = 14.5.sp,
                            color = Uu.Text,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(3.dp))
                        MutedLine("Videos are saved here when they finish.", color = Uu.Faint)
                    }
                    Icon(
                        Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                        contentDescription = null,
                        tint = Uu.Faint,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            if (vm.destination is Destination.Custom) {
                Spacer(Modifier.height(10.dp))
                Box(
                    Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { vm.useDefaultDestination() }
                        .padding(horizontal = 6.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = "Use the default folder instead",
                        style = MaterialTheme.typography.labelLarge,
                        fontSize = 13.sp,
                        color = Uu.Text.copy(alpha = 0.8f),
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            MutedLine(
                "Videos are saved to Movies, where the gallery indexes them with their dates. " +
                    "Pick any other folder if you would rather keep them elsewhere.",
                color = Uu.Faint,
                maxLines = Int.MAX_VALUE,
            )

            Spacer(Modifier.height(26.dp))
            Eyebrow("Storage")
            Spacer(Modifier.height(10.dp))
            StoragePanel(vm)

            Spacer(Modifier.height(26.dp))
            Eyebrow("Default quality")
            Spacer(Modifier.height(10.dp))
            Panel(Modifier.fillMaxWidth()) {
                Column {
                    QualityRow(
                        title = "HQ",
                        detail = vm.highQualityDetail(),
                        selected = vm.quality == Quality.HIGH,
                        onClick = { vm.selectQuality(Quality.HIGH) },
                    )
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .height(1.dp)
                            .background(Uu.Hairline)
                    ) {}
                    QualityRow(
                        title = "Normal",
                        detail = "Up to 720p. Smaller, quicker, fine for most things.",
                        selected = vm.quality == Quality.NORMAL,
                        onClick = { vm.selectQuality(Quality.NORMAL) },
                    )
                }
            }

            Spacer(Modifier.height(26.dp))
            Eyebrow("Download engine")
            Spacer(Modifier.height(10.dp))
            Panel(Modifier.fillMaxWidth()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "yt-dlp ${vm.engineVersion ?: "…"}",
                            style = MaterialTheme.typography.titleMedium,
                            fontSize = 14.5.sp,
                            color = Uu.Text,
                        )
                        Spacer(Modifier.height(3.dp))
                        MutedLine(
                            if (vm.updatingEngine) "Fetching the latest release…"
                            else "The sites change; this is what follows them.",
                            color = Uu.Faint,
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(9.dp))
                            .border(1.dp, Uu.Hairline, RoundedCornerShape(9.dp))
                            .clickable(enabled = !vm.updatingEngine) { vm.updateEngine(onEngineMessage) }
                            .padding(horizontal = 12.dp, vertical = 7.dp)
                    ) {
                        Text(
                            text = if (vm.updatingEngine) "Updating" else "Update",
                            style = MaterialTheme.typography.labelLarge,
                            fontSize = 13.sp,
                            color = if (vm.updatingEngine) Uu.Faint else Uu.Text.copy(alpha = 0.8f),
                        )
                    }
                }
            }

            Spacer(Modifier.height(26.dp))
            Eyebrow("App updates")
            Spacer(Modifier.height(10.dp))
            Panel(Modifier.fillMaxWidth()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "Version ${BuildConfig.VERSION_NAME}",
                            style = MaterialTheme.typography.titleMedium,
                            fontSize = 14.5.sp,
                            color = Uu.Text,
                        )
                        Spacer(Modifier.height(3.dp))
                        val status = when {
                            vm.updateBusy == UpdateBusy.DOWNLOADING ->
                                "Downloading ${((vm.updateProgress ?: 0f) * 100).toInt()}%"
                            vm.updateBusy == UpdateBusy.CHECKING -> "Checking GitHub…"
                            vm.appUpdate != null -> "${vm.appUpdate?.version} is available"
                            else -> vm.updateNote ?: "Checked against GitHub releases"
                        }
                        MutedLine(status, color = if (vm.appUpdate != null) Uu.Text else Uu.Faint)
                    }
                    Spacer(Modifier.width(10.dp))
                    val installable = vm.appUpdate != null
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(9.dp))
                            .border(
                                1.dp,
                                if (installable) Uu.Text.copy(alpha = 0.45f) else Uu.Hairline,
                                RoundedCornerShape(9.dp),
                            )
                            .clickable(enabled = vm.updateBusy == UpdateBusy.NONE) {
                                if (installable) vm.openAppDownload() else vm.checkAppUpdate()
                            }
                            .padding(horizontal = 12.dp, vertical = 7.dp)
                    ) {
                        Text(
                            text = when {
                                vm.updateBusy != UpdateBusy.NONE -> "Working"
                                installable -> if (vm.selfUpdating) "Update" else "Get it"
                                else -> "Check"
                            },
                            style = MaterialTheme.typography.labelLarge,
                            fontSize = 13.sp,
                            color = if (vm.updateBusy == UpdateBusy.NONE) Uu.Text.copy(alpha = 0.85f) else Uu.Faint,
                        )
                    }
                }
            }

            if (vm.selfUpdating) {
                Spacer(Modifier.height(10.dp))
                Panel(
                    Modifier.fillMaxWidth(),
                    border = Uu.Danger.copy(alpha = 0.55f),
                    background = Uu.Danger.copy(alpha = 0.08f),
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text(
                            text = "This build updates itself",
                            style = MaterialTheme.typography.titleMedium,
                            fontSize = 13.5.sp,
                            color = Uu.Danger,
                        )
                        Spacer(Modifier.height(5.dp))
                        MutedLine(
                            "Downloading and installing an APK is what Play Protect reports as a " +
                                "harmful app, and that report is correct about the behaviour. If you " +
                                "would rather not see it, use the universal or architecture-specific " +
                                "build instead — those only open the release page.",
                            color = Uu.Text.copy(alpha = 0.75f),
                        )
                    }
                }
            }

            Spacer(Modifier.height(26.dp))
            Eyebrow("Sites that need an account")
            Spacer(Modifier.height(10.dp))
            Panel(Modifier.fillMaxWidth()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(Uu.Text.copy(alpha = 0.07f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Outlined.Lock,
                            contentDescription = null,
                            tint = Uu.Text.copy(alpha = 0.85f),
                            modifier = Modifier.size(17.dp),
                        )
                    }
                    Spacer(Modifier.width(13.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = if (vm.cookiesAdded) "Browser cookies" else "Not signed in",
                            style = MaterialTheme.typography.titleMedium,
                            fontSize = 14.5.sp,
                            color = Uu.Text,
                        )
                        Spacer(Modifier.height(3.dp))
                        MutedLine(
                            if (vm.cookiesAdded) {
                                "${vm.cookieCount} cookies · Reddit, private posts and other gated sites"
                            } else {
                                "Reddit, private posts and other gated sites"
                            },
                            color = Uu.Faint,
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(9.dp))
                            .border(1.dp, Uu.Hairline, RoundedCornerShape(9.dp))
                            .clickable {
                                if (vm.cookiesAdded) vm.removeCookies() else onPickCookies()
                            }
                            .padding(horizontal = 12.dp, vertical = 7.dp)
                    ) {
                        Text(
                            text = if (vm.cookiesAdded) "Remove" else "Add",
                            style = MaterialTheme.typography.labelLarge,
                            fontSize = 13.sp,
                            color = Uu.Text.copy(alpha = 0.8f),
                        )
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            MutedLine(
                "Export cookies.txt from a browser you're signed into, then pick it here. " +
                    "It stays on this device.",
                color = Uu.Faint,
                maxLines = Int.MAX_VALUE,
            )

            Spacer(Modifier.height(26.dp))
            Eyebrow("About")
            Spacer(Modifier.height(10.dp))
            Panel(Modifier.fillMaxWidth()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenAbout)
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "UU Downloader",
                            style = MaterialTheme.typography.titleMedium,
                            fontSize = 14.5.sp,
                            color = Uu.Text,
                        )
                        Spacer(Modifier.height(3.dp))
                        MutedLine("Version ${BuildConfig.VERSION_NAME} · UU Labs", color = Uu.Faint)
                    }
                    Icon(
                        Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                        contentDescription = null,
                        tint = Uu.Faint,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            Spacer(Modifier.height(36.dp))
        }
    }
}

/**
 * How much room the app may take, as a number or as a share of the phone.
 *
 * A phone's storage is the one resource a downloader can exhaust without the user noticing,
 * so the allowance is set here, enforced before a download starts, and shown as a bar rather
 * than a sentence.
 */
@Composable
private fun StoragePanel(vm: UuViewModel) {
    val limit = vm.storageLimit
    val limitBytes = StorageLimit.limitBytes(LocalContext.current, limit)
    val used = vm.usageBytes
    val fraction = if (limitBytes > 0) (used.toFloat() / limitBytes).coerceIn(0f, 1f) else 0f
    val value = when (limit.mode) {
        LimitMode.GIGABYTES -> limit.gigabytes
        LimitMode.PERCENT -> limit.percent
    }
    val range = when (limit.mode) {
        LimitMode.GIGABYTES -> StorageLimit.MIN_GIGABYTES.toFloat()..50f
        LimitMode.PERCENT -> 1f..90f
    }

    Panel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Allowed",
                    style = MaterialTheme.typography.titleMedium,
                    fontSize = 14.5.sp,
                    color = Uu.Text,
                )
                Spacer(Modifier.weight(1f))
                MutedLine(
                    "${ByteSize.human(used)} of ${ByteSize.human(limitBytes)}",
                    color = if (fraction > 0.9f) Uu.Danger else Uu.Muted,
                )
            }
            Spacer(Modifier.height(10.dp))
            ProgressLane(fraction)
            Spacer(Modifier.height(16.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LimitMode.entries.forEach { mode ->
                    val selected = mode == limit.mode
                    Box(
                        Modifier
                            .weight(1f)
                            .height(36.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (selected) Uu.Text.copy(alpha = 0.10f) else Color.Transparent)
                            .border(
                                1.dp,
                                if (selected) Uu.Text.copy(alpha = 0.30f) else Uu.Hairline,
                                RoundedCornerShape(10.dp),
                            )
                            .clickable {
                                vm.updateStorageLimit(
                                    when (mode) {
                                        LimitMode.GIGABYTES -> limit.copy(mode = mode)
                                        LimitMode.PERCENT -> limit.copy(mode = mode)
                                    }
                                )
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = when (mode) {
                                LimitMode.GIGABYTES -> "Gigabytes"
                                LimitMode.PERCENT -> "Percent"
                            },
                            style = MaterialTheme.typography.labelLarge,
                            fontSize = 13.sp,
                            color = if (selected) Uu.Text else Uu.Muted,
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            // A stepper rather than a slider: a limit is a number people say out loud
            // ("five gigs"), and a slider with 49 stops draws a ruler.
            Row(verticalAlignment = Alignment.CenterVertically) {
                StepButton("−", enabled = value > range.start.toInt()) {
                    vm.updateStorageLimit(
                        when (limit.mode) {
                            LimitMode.GIGABYTES -> limit.copy(gigabytes = value - 1)
                            LimitMode.PERCENT -> limit.copy(percent = value - 1)
                        }
                    )
                }
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Text(
                        text = when (limit.mode) {
                            LimitMode.GIGABYTES -> "$value GB"
                            LimitMode.PERCENT -> "$value%"
                        },
                        style = MaterialTheme.typography.headlineSmall,
                        fontSize = 20.sp,
                        color = Uu.Text,
                    )
                }
                StepButton("+", enabled = value < range.endInclusive.toInt()) {
                    vm.updateStorageLimit(
                        when (limit.mode) {
                            LimitMode.GIGABYTES -> limit.copy(gigabytes = value + 1)
                            LimitMode.PERCENT -> limit.copy(percent = value + 1)
                        }
                    )
                }
            }

            MutedLine("Downloads stop when this is reached.", color = Uu.Faint)
        }
    }
}

@Composable
private fun StepButton(symbol: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(42.dp)
            .clip(CircleShape)
            .border(1.dp, if (enabled) Uu.Hairline else Uu.Hairline.copy(alpha = 0.4f), CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = symbol,
            style = MaterialTheme.typography.headlineSmall,
            fontSize = 20.sp,
            color = if (enabled) Uu.Text else Uu.Faint,
        )
    }
}

@Composable
private fun QualityRow(title: String, detail: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontSize = 14.5.sp,
                color = if (selected) Uu.Text else Uu.Muted,
            )
            Spacer(Modifier.height(3.dp))
            MutedLine(detail, color = if (selected) Uu.Muted else Uu.Faint)
        }
        Spacer(Modifier.width(12.dp))
        Box(
            Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(if (selected) Uu.Text else Uu.SurfaceHigh)
                .border(1.dp, if (selected) Uu.Text else Uu.Hairline, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Icon(
                    Icons.Outlined.Check,
                    contentDescription = null,
                    tint = Uu.Ink,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}

@Composable
fun RoundIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(40.dp)
            .clip(CircleShape)
            .border(1.dp, Uu.Hairline, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = Uu.Muted,
            modifier = Modifier.size(18.dp),
        )
    }
}
