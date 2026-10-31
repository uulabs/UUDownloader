package au.com.uulabs.uudownloader.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import au.com.uulabs.uudownloader.data.Destination
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import au.com.uulabs.uudownloader.UuViewModel

private enum class Screen { HOME, SETTINGS, ABOUT }

@Composable
fun UuRoot(vm: UuViewModel) {
    val context = LocalContext.current
    val items by vm.items.collectAsStateWithLifecycle()
    var screen by remember { mutableStateOf(Screen.HOME) }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { picked ->
            // Persisting the grant is what makes the folder survive a reboot; without it the
            // download works until the next launch and then fails with no explanation.
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            runCatching { context.contentResolver.takePersistableUriPermission(picked, flags) }
            vm.chooseDestination(Destination.Custom(picked, folderLabel(context, picked)))
        }
    }

    // The picker opens on Download, which is where a downloaded video belongs. Android refuses
    // to grant the Download folder itself as a writable tree, so the screen says "can't use this
    // folder" there and the user creates or picks a folder inside it.
    val startFolder = remember {
        runCatching {
            DocumentsContract.buildDocumentUri(
                "com.android.externalstorage.documents",
                "primary:Download",
            )
        }.getOrNull()
    }

    BackHandler(enabled = screen != Screen.HOME) { screen = Screen.HOME }

    Box(Modifier.fillMaxSize().background(Uu.Ink)) {
        TopGlow(Modifier.align(Alignment.TopCenter))

        Box(
            Modifier
                .fillMaxSize()
                .imePadding()
                .windowInsetsPadding(WindowInsets.safeDrawing)
        ) {
            Crossfade(
                targetState = screen,
                animationSpec = tween(180),
                label = "screen",
            ) { current ->
                when (current) {
                    Screen.HOME -> HomeScreen(
                        vm = vm,
                        items = items,
                        onOpenSettings = { screen = Screen.SETTINGS },
                        onChooseFolder = { folderPicker.launch(startFolder) },
                        onOpenFile = { uri -> openVideo(context, uri) },
                    )

                    Screen.SETTINGS -> SettingsScreen(
                        vm = vm,
                        onBack = { screen = Screen.HOME },
                        onChooseFolder = { folderPicker.launch(startFolder) },
                        onOpenAbout = { screen = Screen.ABOUT },
                    )

                    Screen.ABOUT -> AboutScreen(onBack = { screen = Screen.SETTINGS })
                }
            }
        }
    }
}

/**
 * A breath of cyan at the top of the screen. It is the only decoration in the app: enough to
 * read as UU Labs, not enough to compete with the wordmark.
 */
@Composable
private fun TopGlow(modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val center = with(density) { Offset(70.dp.toPx(), (-90).dp.toPx()) }
    val radius = with(density) { 560.dp.toPx() }
    Box(
        modifier
            .fillMaxWidth()
            .height(340.dp)
            .background(
                Brush.radialGradient(
                    colors = listOf(
                        Uu.Text.copy(alpha = 0.05f),
                        Uu.Text.copy(alpha = 0.02f),
                        Color.Transparent,
                    ),
                    center = center,
                    radius = radius,
                )
            )
    )
}

private fun folderLabel(context: Context, uri: Uri): String {
    val fromTree = runCatching { DocumentFile.fromTreeUri(context, uri)?.name }.getOrNull()
    if (!fromTree.isNullOrBlank()) return fromTree
    return uri.lastPathSegment?.substringAfterLast(':')?.takeIf { it.isNotBlank() } ?: "Folder"
}

private fun openVideo(context: Context, uri: String) {
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(Uri.parse(uri), "video/*")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    val opened = runCatching { context.startActivity(intent) }.isSuccess
    if (!opened) {
        Toast.makeText(context, "Nothing on this device can open that video", Toast.LENGTH_SHORT).show()
    }
}
