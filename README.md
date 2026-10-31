# UU Downloader

A small Android app that saves videos from the socials. Paste a link, or share one straight
from Instagram, TikTok, X, Facebook or YouTube, and it downloads in the notification shade.

Dark, quiet, and the same family as UU Chat and LevelPod: black surfaces, one lit thing per
screen, nothing to configure before it works.

```
UU LABS
UU Downloader
─────────────
SAVE TO      Downloads / UU Downloader    [Change]
┌──────────────────────────────────────────────┐
│ 🔗  Paste a link                          📋 │
└──────────────────────────────────────────────┘
  [Y]  Me at the zoo
QUALITY
[  Normal  ][    HQ    ]
This one tops out at 240p, so Normal is the same file.
┌──────────────────────────────────────────────┐
│                  Download                    │
└──────────────────────────────────────────────┘
```

## What it does

- **Share target.** It appears in the Android share sheet for text links. With everything set
  up, sharing a reel from Instagram starts the download and the app gets out of the way — the
  progress lives in the notification, not on screen.
- **Paste and download.** The field takes a link (and finds the link inside a shared caption,
  which is what Instagram and Facebook actually send).
- **Normal or HQ.** HQ is offered only when the source can actually beat 720p; when it cannot,
  the button disappears rather than handing the user two names for the same file.
- **A folder that just works.** Videos land in `Download/UU Downloader` with no setup, no
  permission prompt. A different folder can be chosen through the system picker.
- **Progress, cancel, and a result.** A silent foreground notification shows percent, size,
  speed and ETA, with a Cancel action. Finished files get their own notification with Open.

## Build

Requires JDK 17+ and an Android SDK with platform 36. Nothing else.

```sh
./gradlew :app:assembleDebug
# app/build/outputs/apk/debug/app-debug.apk
```

Install it with `adb install -r app/build/outputs/apk/debug/app-debug.apk`.

The APK is large (~115 MB) because it carries, for both ARM ABIs, a Python runtime, yt-dlp and
ffmpeg — see the note below.

### Tests

```sh
./gradlew :app:testDebugUnitTest
```

The tests cover the parts that can be wrong without a phone: link detection, what the quality
buttons mean, filename safety, yt-dlp progress parsing.

## How it downloads

Two engines, picked by what the link is:

| Link | Engine | Why |
| --- | --- | --- |
| ends in `.mp4` / `.webm` / `.mov` … | plain HTTP | it is already a file; no reason to wait for a runtime to start |
| anything else | [yt-dlp](https://github.com/yt-dlp/yt-dlp) on a bundled Python runtime | it is the only thing that keeps up with YouTube, TikTok, Instagram, Facebook and X |

The yt-dlp engine comes from [youtubedl-android](https://github.com/yausername/youtubedl-android)
(`0.18.1`), with the matching ffmpeg module for merging separate video and audio streams into
one MP4.

## Limitations, honestly

- **Some posts need a login.** Instagram and Facebook increasingly serve reels and videos only
  to signed-in sessions. yt-dlp will report that as an error; the app shows the message.
- **A private or region-locked video will not download,** and neither will a live stream.
- **Quality is whatever the source offers.** "HQ" means best available, not 4K by decree.
- **One download at a time.** A second shared link waits for the first to finish.

## Licence note

`youtubedl-android` is **GPL-3.0**. Linking it makes this app GPL-3.0 as a whole. That is fine
for a personal UU Labs app whose source lives in this repository, but it is a real constraint
if UU Downloader is ever distributed on a store: the corresponding source has to be available
under the same terms. yt-dlp itself is Unlicense; the Python runtime and ffmpeg carry their own
licences.

## Layout

```
app/src/main/kotlin/au/com/uulabs/uudownloader/
  core/        pure Kotlin: links, quality, filenames — no Android, fully tested
  data/        preferences and the destination model
  download/    engines, writers, foreground service, notifications
  ui/          Compose screens and the black-and-white design system
icon/          the launcher art and the script that generates every density from it
docs/          design notes and the screenshots in this README
```

Made by UU Labs, 2026.
