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
import androidx.compose.material.icons.rounded.FolderOpen
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
import au.com.uulabs.uudownloader.BuildConfig
import au.com.uulabs.uudownloader.UuViewModel
import au.com.uulabs.uudownloader.core.Quality
import au.com.uulabs.uudownloader.data.Destination

@Composable
fun SettingsScreen(
    vm: UuViewModel,
    onBack: () -> Unit,
    onChooseFolder: () -> Unit,
    onPickCookies: () -> Unit,
    onOpenAbout: () -> Unit,
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
                            Icons.Rounded.FolderOpen,
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
                "Android won't let an app write into Download itself, so videos go in a folder " +
                    "inside it.",
                color = Uu.Faint,
            )

            Spacer(Modifier.height(26.dp))
            Eyebrow("Default quality")
            Spacer(Modifier.height(10.dp))
            Panel(Modifier.fillMaxWidth()) {
                Column {
                    QualityRow(
                        title = "HQ",
                        detail = "The best the site offers. Bigger files, sharper picture.",
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
