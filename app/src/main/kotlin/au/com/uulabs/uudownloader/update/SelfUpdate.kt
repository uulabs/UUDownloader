package au.com.uulabs.uudownloader.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import au.com.uulabs.uudownloader.BuildConfig
import au.com.uulabs.uudownloader.UuLog
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloading and installing the app's own update.
 *
 * This is the behaviour Google Play Protect calls a dropper, and it says so on install. It is
 * therefore only reachable from the `autoupdate` build, which exists for people who would rather
 * have one-tap updates than a clean scanner report. The standard builds compile this class but
 * never call it, and their manifest carries neither the install permission nor the provider.
 *
 * Android still shows its own confirmation. Nothing here bypasses it.
 */
object SelfUpdate {

    /** The APK for this phone: its own architecture first, then the universal one. */
    fun pickAsset(assets: List<ReleaseAsset>): ReleaseAsset? {
        val abi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
        val wanted = when {
            abi.contains("arm64") -> "arm64"
            abi.contains("armeabi") || abi.contains("v7") -> "armv7"
            else -> null
        }
        // The autoupdate build ships as the universal APK; the architecture-named files are the
        // standard builds, which cannot install themselves.
        return assets.firstOrNull { it.name.contains("universal", true) && wanted == null }
            ?: assets.firstOrNull { it.name.contains("universal-autoupdate", true) }
            ?: assets.firstOrNull { wanted != null && it.name.contains("$wanted-autoupdate", true) }
            ?: assets.firstOrNull { it.name.contains("universal", true) }
            ?: assets.firstOrNull()
    }

    fun canInstall(context: Context): Boolean = runCatching {
        context.packageManager.canRequestPackageInstalls()
    }.getOrDefault(true)

    /** Straight to the toggle, rather than to Settings at large. */
    fun openInstallPermission(context: Context) {
        val intent = Intent(
            android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    fun download(context: Context, asset: ReleaseAsset, onProgress: (Float) -> Unit): File {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val target = File(dir, asset.name)

        // A first attempt can stop at Android's "allow installs from this source" prompt; the
        // bytes are already here, so that detour must not cost another download.
        if (target.exists() && asset.sizeBytes > 0 && target.length() == asset.sizeBytes) {
            UuLog.i("self update: reusing cached ${asset.name}")
            return target
        }
        if (target.exists()) target.delete()

        val connection = (URL(asset.url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 20_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", "UU-Downloader-Android")
            setRequestProperty("Accept", "application/octet-stream")
        }
        try {
            if (connection.responseCode !in 200..299) {
                throw IllegalStateException("Download failed (${connection.responseCode})")
            }
            val total = connection.contentLengthLong.takeIf { it > 0 } ?: asset.sizeBytes
            var copied = 0L
            var last = 0L
            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(128 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        copied += read
                        val now = System.currentTimeMillis()
                        if (now - last > 300) {
                            last = now
                            onProgress(if (total > 0) copied.toFloat() / total else 0f)
                        }
                    }
                }
            }
            return target
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Whether the downloaded APK carries this app's signing key.
     *
     * Android refuses an in-place update across keys and only says "App not installed". The
     * installed app's certificate digest is compared with the one this build was signed with,
     * which is a constant known at build time.
     */
    fun signedLikeInstalled(context: Context): Boolean {
        if (BuildConfig.DEBUG) return true
        val expected = BuildConfig.UU_CERT_SHA256.lowercase().replace(":", "")
        val installed = runCatching {
            val info = context.packageManager.getPackageInfo(
                context.packageName,
                android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES,
            )
            val certificate = info.signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray()
                ?: return@runCatching null
            java.security.MessageDigest.getInstance("SHA-256")
                .digest(certificate)
                .joinToString("") { "%02x".format(it) }
        }.getOrNull() ?: return true
        return installed == expected
    }

    fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    @Suppress("unused")
    private fun pendingInstall(context: Context, apk: File): PendingIntent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(
            context,
            2,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
