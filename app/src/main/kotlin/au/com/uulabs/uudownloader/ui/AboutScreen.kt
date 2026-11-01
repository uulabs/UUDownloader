package au.com.uulabs.uudownloader.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import au.com.uulabs.uudownloader.BuildConfig
import au.com.uulabs.uudownloader.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun AboutScreen(onBack: () -> Unit, onOpenLicences: () -> Unit) {
    val context = LocalContext.current
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
            ScreenTitle("About")
        }

        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Image(
                painter = painterResource(R.drawable.uu_mark),
                contentDescription = null,
                modifier = Modifier.size(96.dp),
            )
            Spacer(Modifier.height(22.dp))
            Text(
                text = "UU Downloader",
                style = MaterialTheme.typography.headlineSmall,
                color = Uu.Text,
            )
            Spacer(Modifier.height(4.dp))
            Eyebrow("UU Labs", color = Uu.Faint)
            Spacer(Modifier.height(26.dp))
            Text(
                text = "UU Downloader is a simple way to save videos you see on the socials.",
                style = MaterialTheme.typography.bodyMedium,
                fontSize = 15.sp,
                color = Uu.Muted,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(14.dp))
            Text(
                text = "Created by UU Labs 2026",
                style = MaterialTheme.typography.bodyMedium,
                color = Uu.Text.copy(alpha = 0.85f),
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(34.dp))
            Panel(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Eyebrow("Works with")
                    Spacer(Modifier.height(9.dp))
                    MutedLine(
                        "YouTube · TikTok · Instagram · Reddit · Facebook · X\nand most other video sites",
                        color = Uu.Text.copy(alpha = 0.8f),
                    )
                    Spacer(Modifier.height(14.dp))
                    Eyebrow("Version")
                    Spacer(Modifier.height(9.dp))
                    MutedLine(
                        "${BuildConfig.VERSION_NAME} (${BuildConfig.UU_COMMIT})",
                        color = Uu.Faint,
                    )
                    Spacer(Modifier.height(14.dp))
                    Eyebrow("Free software")
                    Spacer(Modifier.height(9.dp))
                    MutedLine(
                        "Licensed under the GNU GPL, version 3 or later.",
                        color = Uu.Faint,
                    )
                    Spacer(Modifier.height(6.dp))
                    LinkLine("Source code for this version") {
                        val tag = "v${BuildConfig.VERSION_NAME}"
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("$SOURCE_URL/tree/$tag"))
                        runCatching { context.startActivity(intent) }
                    }
                    LinkLine("Licences and notices", onOpenLicences)
                }
            }

            Spacer(Modifier.height(22.dp))
            Text(
                text = "Only save videos you have the right to keep. Everything is downloaded by the app on this device; nothing is uploaded anywhere.",
                style = MaterialTheme.typography.bodySmall,
                fontSize = 12.5.sp,
                color = Uu.Faint,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(40.dp))
        }
    }
}

private const val SOURCE_URL = "https://github.com/uulabs/UUDownloader"

@Composable
private fun LinkLine(text: String, onClick: () -> Unit) {
    Text(
        text = text,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 7.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = Uu.Text.copy(alpha = 0.85f),
    )
}

/** The notices the licences of everything in this app ask to be shown, read from assets. */
@Composable
fun LicencesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val text by produceState("") {
        value = withContext(Dispatchers.IO) {
            listOf("licenses/THIRD-PARTY-NOTICES.txt", "licenses/GPL-3.0.txt").joinToString("\n\n") { name ->
                runCatching { context.assets.open(name).bufferedReader().use { it.readText() } }.getOrDefault("")
            }
        }
    }
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
            ScreenTitle("Licences")
        }
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodySmall,
            color = Uu.Muted,
        )
        Spacer(Modifier.height(40.dp))
    }
}
