package au.com.uulabs.uudownloader.data

import android.net.Uri

/**
 * Where a finished video lands.
 *
 * Two shapes, because Android gives an app two different ways to write and neither covers
 * both cases. The default is [Downloads]: the phone's Downloads folder via MediaStore, which
 * needs no permission and no ceremony, so sharing a link works on a phone that has never had
 * this app opened before. [Custom] is a folder the user picked with the system picker, which
 * is the only way to write anywhere else.
 */
sealed interface Destination {

    val label: String

    data object Downloads : Destination {
        override val label: String = "Downloads / UU Downloader"
    }

    data class Custom(val uri: Uri, val name: String) : Destination {
        override val label: String get() = name
    }

    companion object {
        /** The sub-folder created inside the phone's Download directory. */
        const val FOLDER_NAME = "UU Downloader"
    }
}
