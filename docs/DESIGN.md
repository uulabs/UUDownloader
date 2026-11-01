# UU Downloader — design notes

These are the decisions that were not obvious, and what they cost.

## 1. The download engine is yt-dlp, and that is the whole design

Every one of the six sites on the tin — YouTube, TikTok, Instagram, Facebook, X, Reddit — actively
breaks third-party extraction. TikTok rotates its endpoints, Instagram hides reels behind a
session, X requires a token dance for some posts, and YouTube's signature handling changes
monthly. A hand-written extractor would work for a fortnight.

yt-dlp is the reference implementation for all of them, and it is what every serious Android
downloader ships (Seal, ytdlnis). So the app embeds:

- `youtubedl-android:library:0.18.1` — a bundled CPython, yt-dlp itself, and the JNI shim
- `youtubedl-android:ffmpeg:0.18.1` — ffmpeg, so separate video and audio streams can be merged

**Cost:** a 54–61 MB APK per ABI, `extractNativeLibs=true` (the runtime executes binaries out of
`nativeLibraryDir`), and GPL-3.0 obligations. **Benefit:** the six sites work, and they keep
working as yt-dlp updates.

A direct link (`…/clip.mp4`) skips all of this and goes through `HttpURLConnection`. The Python
runtime takes seconds to start; a CDN file deserves none of that. If the server answers with a web
page instead of media, nothing is written and the job goes to yt-dlp, because a page saved as a
video would report success and open as nothing.

### yt-dlp names its own result

The engine used to take the largest file in the job's folder. That was a guess, and it guessed
wrong in the worst way: a refused HQ run could leave a video-only stream behind, which was then
larger than the Normal file saved after it, so the user got a silent video and a success message.
Now every attempt starts from an empty folder, and yt-dlp writes the path, title and playlist size
to files beside the video (`--print-to-file`). A run that names no file produced no video.

The same run is limited to one item. `--no-playlist` only covers a video that happens to sit in a
playlist; a link that *is* a playlist or a channel would otherwise download all of it and keep one.
Live streams are filtered out, because they never end.

### "Best quality" means the best this phone can play

Left alone, yt-dlp takes the best stream a site offers, which on YouTube is often 4K AV1. Neither
test phone has an AV1 hardware decoder, so that gave a very large file the phone could not play
smoothly, and the app called it a success.

So the app asks the phone. `DeviceCodecs` reads Android's decoder list for AV1, VP9, HEVC and H.264,
and records the largest picture each manages at 30 and at 60 frames a second. Only hardware decoders
count, because software decoders accept 4K AV1 on paper and then stutter through it on a phone's CPU.
The exception is H.264 up to 720p, which any phone's CPU can handle.

Paper limits are not trusted on their own. Measured frame rates decide where a phone publishes them,
then its performance points, and with neither only 1080p30 is taken on trust. Only the landscape
figure is asked about: one real phone allows 1920 wide by 3840 tall, which passes a portrait 1440x2560
and then refuses the landscape video. An early version of this checked both orientations and saved a
1440p60 file that phone reported as unsupported.

`PlaybackLimits` turns that into a list for yt-dlp, which takes the first entry that exists. It runs
from the largest picture down, because resolution is what people notice, and each size band has a
floor as well as a ceiling: without one, a 480p VP9 stream would satisfy "VP9 up to 4K" and beat the
1080p H.264 stream beside it. At any given size H.264 comes first, because a hardware decoder is not
the whole story: a gallery app or the next app the file is shared to may still refuse VP9 in an MP4,
while everything opens H.264. The other codecs are there for sizes H.264 is not offered in. Sizes are matched on the short edge, so a portrait 1080x1920 clip asks
the same of a decoder as a landscape 1920x1080 one. HDR streams are left out, and AAC audio is preferred.

If a source offers nothing the phone can decode, it still saves something, kept within the phone's
largest size, because a file that may be hard to play beats an error.

### One engine per process

The screen and the service share a single `YtDlpEngine`, because what it guards is per process: the
unpacked runtime, the file on disk, and whether that file is being replaced. Two instances meant
the service could never see an update running on the screen's instance.

### The probe is a convenience, never a gate

To know whether "HQ" is worth offering, the app asks yt-dlp for the video's metadata. On a slow
phone that can take 40 seconds. So the probe:

- never blocks the Download button,
- runs under its own process id with a 45-second watchdog that kills it,
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

Filenames are capped in bytes as well as characters, because the filesystem counts bytes and 90
Japanese characters are 270 of them, and they stop on a whole character so an emoji is never cut
in half.

The allowance has its own ledger rather than reading the history list. History keeps thirty rows
for the screen; a video does not stop taking up room because its row scrolled away. Before each job
and whenever Settings shows the figure, the ledger asks the provider which files still exist. A
delete the system refuses keeps the row, because that row is the only way back to the file.

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

The service enters the foreground once, as soon as it is started, and stays there until the queue
is empty. Android will not let an app in the background re-enter the foreground, and sharing from
another app is exactly when this app is in the background, so dropping out between two jobs would
crash on the second. A watchdog on its own clock handles the storage limit and pending cancels,
because progress lines are not guaranteed and a cancel can land before the process it should kill
exists. A partial wake lock keeps the CPU up with the screen off, and Android 15's daily limit for
this kind of service ends the job with a reason rather than a crash. Job folders left by a dead
process are cleared at the next start.

## 4. Sharing leaves the app

A share launch with a working destination enqueues, starts the service, shows a toast, and
closes its task. The user stays in Instagram; the progress is in the shade. That is the entire
premise of the feature, so the app must not stand in front of it.

A share is one request. The system hands the same intent back on rotation and from Recents, so it
only counts on a genuinely new launch.

Two exceptions keep the screen up: no notification permission (the user would see nothing at
all), and a link the app cannot use.

## 5. Black and white

The first draft of the icon was neon cyan on navy — UU Chat's palette. It was rejected: *"needs
to be black and tidy."* The icon became a matte black tile with a white mark, and the app
followed: no accent colour, one lit thing per screen, white only for the primary action and
progress.

The launcher art is generated, not hand-drawn in a design tool: `icon/build_icon.py` draws
every density (legacy, round, adaptive background and foreground) from one function so the
mark cannot drift between them.

## 5a. The two bugs that only appeared in release

**R8 against a reflective dependency.** youtubedl-android unpacks its Python payload with Apache
Commons Compress, which registers extra-field handlers from a static list of class literals and
instantiates them by name. Shrinking removed one of those classes, so the very first unzip threw
`NoClassDefFoundError: ExtraFieldUtils`, the runtime never came up, and every download of every
site failed. It survived testing because the one device that passed had a leftover unpacked
Python from an earlier debug build — the debug path never exercises the same bytecode. The fix
is a keep rule; the lesson is that a first-run path tested only on a dirty install is not tested.

**Obfuscation against the person holding the phone.** With obfuscation on, the exception above
reached the user as the string `i5.f`: a renamed class, no message, no way for anyone to act on
it. The app is 97% native payload, so obfuscation saved almost nothing and cost the entire
diagnosis. Release builds now keep their names, log every job to the `UUDL` tag, and print the
throwing class when a message is missing.

## 5b. The update that had to be taken back

yt-dlp ships as a snapshot and a snapshot goes stale, so the obvious idea was to refresh it
quietly once per install. That was wrong, and it cost a stalled download to find out why:
`youtubedl-android` holds one monitor across `init` and `updateYoutubeDL`, and the updater's
metadata fetch has no read timeout. A quiet update on a slow network therefore blocked the first
download forever, on "Getting ready", with nothing on screen to explain it.

The update is still here, but only where a person asks for it. It no longer goes through the
library at all: the library's updater holds that monitor, has no timeout and checks nothing. The
app fetches the release itself with bounded timeouts, verifies it against yt-dlp's published
SHA2-256SUMS, renames it into place only when no run is using the engine, and puts the old one back
if the new one will not start. A job that arrives during the swap says "Waiting for an engine
update".

## 6. Where the line is: accounts and regions

Two things came up that the app deliberately does not do.

**It does not defeat authentication.** Reddit's video API refuses anonymous clients, so the
obvious "fix" is to mint the tokens a browser would mint — scrape the page, replay the signing
dance, look like a logged-in client. That is circumventing an access control the operator put
there on purpose, and it is both a terms-of-service problem and, in several jurisdictions, a
legal one. What the app does instead: accept a `cookies.txt` the user exported from *their own*
browser session, pass it to yt-dlp, and say plainly when a site wants a session. The user's own
credentials are consent; forged ones are not.

**It does not defeat geography.** Where a site blocks a country — including the age-assurance
regimes some sites answer by refusing a whole region — the app reports the site's own error.
A VPN on the phone is the phone's business and the app inherits it, because it has no network
layer of its own: one `HttpURLConnection` per direct link, one yt-dlp child process otherwise,
both on the system resolver. There is no proxy support and no fake locale, and adding a "bypass" mode to this app would be
building a different, worse product.

Two things sit closer to that line and are stated rather than hidden. On a YouTube bot check the
app retries with yt-dlp's other client settings, because the check is aimed at the network and the
same video is often served to another client. And the direct engine sends a phone browser's user
agent, because CDNs commonly refuse an unfamiliar one.

The honest position is also the useful one: everything that *is* publicly reachable works, and
that is the overwhelming majority of what people share.

## 7. What is deliberately absent

- **No playback.** Saving is the job; opening the file is one tap away and the system owns it.
- **No account, no server, no upload.** The only network traffic is the request to fetch the
  video and the update check against GitHub, both made by the device.
- **No queue UI, no thumbnails.** One active download, a short recent list, done.
- **No download-on-Wi-Fi-only switch.** It was considered and cut: the notification already
  shows speed, and a wrong guess about the user's data plan is worse than none.
