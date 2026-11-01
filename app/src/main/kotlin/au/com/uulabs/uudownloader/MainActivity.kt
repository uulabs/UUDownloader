package au.com.uulabs.uudownloader

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import au.com.uulabs.uudownloader.ui.UuRoot
import au.com.uulabs.uudownloader.ui.UuTheme

/**
 * The app's only activity. It is also the share target, which is the whole point: a link shared
 * from Instagram lands here, starts downloading, and the screen gets out of the way.
 */
class MainActivity : ComponentActivity() {

    private val vm: UuViewModel by viewModels()

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The app is dark on every device, so the system bars are forced dark too. Left to the
        // default, a phone in light mode draws dark status icons on our black background and
        // they disappear.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )

        // The system hands the same intent back on every rotation, and again when the task is
        // reopened from Recents. A share is one request, so only a genuinely new launch counts.
        val fromHistory = intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0
        val outcome = if (savedInstanceState == null && !fromHistory) {
            takeSharedText(intent)?.let(vm::handleIncomingText) ?: ShareOutcome.NONE
        } else {
            ShareOutcome.NONE
        }

        // A share with everything already set up should leave the user exactly where they were:
        // in the other app, watching. The download lives in the notification shade.
        if (outcome == ShareOutcome.STARTED && notificationsAreVisible()) {
            Toast.makeText(this, "Saving your video…", Toast.LENGTH_SHORT).show()
            finishAndRemoveTask()
            return
        }

        askForNotificationPermission()
        setContent {
            UuTheme {
                UuRoot(vm)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        takeSharedText(intent)?.let(vm::handleIncomingText)
    }

    /**
     * Share text is not a URL. Instagram and Facebook append the caption, so the link has to be
     * found inside it rather than taken as the whole string.
     */
    private fun takeSharedText(intent: Intent?): CharSequence? {
        if (intent?.action != Intent.ACTION_SEND) return null
        val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)
        setIntent(Intent(this, MainActivity::class.java))
        return text
    }

    private fun notificationsAreVisible(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        }
        return NotificationManagerCompat.from(this).areNotificationsEnabled()
    }

    private fun askForNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (notificationsAreVisible()) return
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
