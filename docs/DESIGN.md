# UU Downloader — design notes

These are the decisions that were not obvious, and what they cost.

## 1. The download engine is yt-dlp, and that is the whole design

Every one of the five sites on the tin — YouTube, TikTok, Instagram, Facebook, X — actively
breaks third-party extraction. TikTok rotates its endpoints, Instagram hides reels behind a
session, X requires a token dance for some posts, and YouTube's signature handling changes
monthly. A hand-written extractor would work for a fortnight.

yt-dlp is the reference implementation for all of them, and it is what every serious Android
downloader ships (Seal, ytdlnis). So the app embeds:

- `youtubedl-android:library:0.18.1` — a bundled CPython, yt-dlp itself, and the JNI shim
- `youtubedl-android:ffmpeg:0.18.1` — ffmpeg, so separate video and audio streams can be merged

**Cost:** ~115 MB APK, `extractNativeLibs=true` (the runtime executes binaries out of
`nativeLibraryDir`), and GPL-3.0 obligations. **Benefit:** the five sites work, and they keep
working as yt-dlp updates.

A direct link (`…/clip.mp4`) skips all of this and goes through `HttpURLConnection`. The Python
runtime takes seconds to start; a CDN file deserves none of that.

### The probe is a convenience, never a gate

To know whether "HQ" is worth offering, the app asks yt-dlp for the video's metadata. On a slow
phone that can take 40 seconds. So the probe:

- never blocks the Download button,
- runs under its own process id with a 20-second watchdog that kills it,
- and only *removes* the HQ button when it has an answer.

The alternative — a "checking…" spinner gating the primary action — makes a 40-second network
call the app's first impression.

## 2. Storage: MediaStore by default, SAF on request

Android 11+ refuses to grant the root of `Download` as a writable tree: the picker says *"Can't
use this folder."* That makes a picker-first design a dead end for the folder everyone actually
wants.

So the default is `MediaStore.Downloads` with `RELATIVE_PATH = Download/UU Downloader`. It
needs no permission, works on a phone that has never opened the app, shows up in Files and the
gallery, and is where a downloaded video belongs.

A custom folder is still available through `ACTION_OPEN_DOCUMENT_TREE` (the picker opens on
`Download` so the user does not have to find it), and the grant is persisted so the choice
survives a reboot.

Items are inserted with `IS_PENDING = 1` and published only after the bytes are copied: a
cancelled download never leaves a half-file the user can tap.

`minSdk` is 29 because of `MediaStore.Downloads`; Android 10 is the floor that avoids the
legacy-storage permission path entirely.

## 3. One job at a time, one list of jobs

`DownloadHub` is a process-wide `StateFlow<List<DownloadItem>>`. `DownloadService` does not own
a queue; it repeatedly takes the first item still in `QUEUED` and runs it until none are left.
That means:

- a link shared while a download is running is picked up with no hand-off,
- the screen and the notification always read the same state,
- cancellation is just moving an item to a terminal state plus killing the child process.

Only terminal items are persisted (`HistoryStore`): a queued item that was never resumed would
come back as a row spinning forever.

## 4. Sharing leaves the app

A share launch with a working destination enqueues, starts the service, shows a toast, and
`finish()`es. The user stays in Instagram; the progress is in the shade. That is the entire
premise of the feature, so the app must not stand in front of it.

Two exceptions keep the screen up: no notification permission (the user would see nothing at
all), and a link the app cannot use.

## 5. Black and white

The first draft of the icon was neon cyan on navy — UU Chat's palette. It was rejected: *"needs
to be black and tidy."* The icon became a matte black tile with a white mark, and the app
followed: no accent colour, one lit thing per screen, white only for the primary action and
progress.

The launcher art is generated, not hand-drawn in a design tool: `icon/generate_icon.py` draws
every density (legacy, round, adaptive background and foreground) from one function so the
mark cannot drift between them.

## 6. What is deliberately absent

- **No playback.** Saving is the job; opening the file is one tap away and the system owns it.
- **No account, no server, no upload.** The only network traffic is the request to fetch the
  video, made by the device.
- **No queue UI, no thumbnails.** One active download, a short recent list, done.
- **No download-on-Wi-Fi-only switch.** It was considered and cut: the notification already
  shows speed, and a wrong guess about the user's data plan is worse than none.
