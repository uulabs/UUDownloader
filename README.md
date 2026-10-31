# UU Downloader

A small Android app that saves videos from the socials. Paste a link, or share one straight
from Instagram, TikTok, X, Facebook, Reddit or YouTube, and it downloads in the notification shade.

Dark, quiet, and the same family as UU Chat and LevelPod: black surfaces, one lit thing per
screen, nothing to configure before it works.

| Home | Saved | Settings |
| --- | --- | --- |
| ![Home](docs/shot-home.png) | ![Progress](docs/shot-progress.png) | ![Settings](docs/shot-settings.png) |

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
- **Sign-in when you have one, never required.** Reddit, private Instagram posts and a few other
  sites will only answer a signed-in request. Settings takes a `cookies.txt` exported from your
  own browser and hands it to the engine. Nothing is signed in by default and nothing else is
  affected if you never use it.
- **Progress, cancel, and a result.** A silent foreground notification shows percent, size,
  speed and ETA, with a Cancel action. Finished files get their own notification with Open.

## Which sites

Whatever yt-dlp supports, which is most of the video web — the app has no allow-list.

The six the app names itself are YouTube, TikTok, Instagram, Facebook, X and Reddit; those get
their own label in the list. Everything else goes through the same engine and is named after
its domain, so a Pornhub or Xvideos link shows up as "Pornhub" or "Xvideos" and downloads
exactly like a YouTube one does.

The bundled build (yt-dlp 2025.11.12, 1041 extractor modules) carries dedicated extractors for
Pornhub, Xvideos, XNXX, RedTube, SpankBang, Eporner, YouPorn, Txxx and Tube8 alongside the
social networks.

**No VPN is involved, and none is needed by the app.** The download goes out on the phone's own
connection — nothing is proxied, tunnelled or routed around anything. If you run a VPN on the
phone, UU Downloader inherits it like any other app, because it has no network layer of its own.

What the app will not do is defeat an access control. If a site blocks a country, requires a
sign-in, or puts up an age check, the app reports the site's own answer in plain words and
stops: it does not scramble its identity, mint tokens, or try to look like somewhere it isn't.
Those are the site's rules to set, and the request either passes them honestly or fails.

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

- **Some posts need a login.** Reddit's video API now refuses anonymous clients outright, and
  Instagram, Facebook and X gate some posts behind a session. Add a `cookies.txt` in Settings if
  you want those; without it the app says so plainly.
- **A country-level block stays a block.** Where a site refuses a region, the app can only
  report it.
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
