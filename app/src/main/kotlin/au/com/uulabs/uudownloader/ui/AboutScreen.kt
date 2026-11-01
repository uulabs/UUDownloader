package au.com.uulabs.uudownloader.ui

import androidx.compose.foundation.Image
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import au.com.uulabs.uudownloader.BuildConfig
import au.com.uulabs.uudownloader.R

@Composable
fun AboutScreen(onBack: () -> Unit) {
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
