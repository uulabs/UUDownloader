package au.com.uulabs.uudownloader

import android.app.Application
import au.com.uulabs.uudownloader.data.UuPrefs
import au.com.uulabs.uudownloader.download.DownloadHub
import au.com.uulabs.uudownloader.download.HistoryStore

/**
 * Attaches history before anything can render it.
 *
 * The download list is read by the screen in the first composition, so the store has to be in
 * place by then. Doing this here rather than in an activity also covers the case where a
 * download finishes while the activity has been recreated.
 */
class UuApp : Application() {

    override fun onCreate() {
        super.onCreate()
        val prefs = UuPrefs(this)
        DownloadHub.attach(
            HistoryStore(
                read = { prefs.historyJson },
                write = { prefs.historyJson = it },
            )
        )

    }
}
