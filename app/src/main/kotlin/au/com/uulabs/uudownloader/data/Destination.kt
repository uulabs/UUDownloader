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

    /**
     * The device's media library, in `Movies/UU Downloader`.
     *
     * It was `Download/UU Downloader` until the dates came up wrong. A row written through the
     * Downloads collection reports DATE_TAKEN as NULL — the provider refuses to store it — and
     * galleries that label by that column showed every video as 1 January 1970. The video
     * collection keeps DATE_TAKEN, but only accepts DCIM, Movies and Pictures, so videos moved
     * to Movies. Android's own guidance puts video there anyway; audio and anything else still
     * goes to Download.
     */
    data object MediaLibrary : Destination {
        override val label: String = "Movies / UU Downloader"
    }

    data class Custom(val uri: Uri, val name: String) : Destination {
        override val label: String get() = name
    }

    companion object {
        /** The sub-folder created inside the phone's Download directory. */
        const val FOLDER_NAME = "UU Downloader"
    }
}
