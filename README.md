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
- **A download engine that can update itself.** Settings shows the yt-dlp version and a button
  that fetches the latest one from its own release feed. Sites change; this is what follows them.
- **Sign-in when you have one, never required.** Reddit, private Instagram posts and a few other
  sites will only answer a signed-in request. Settings takes a `cookies.txt` exported from your
  own browser and hands it to the engine. Nothing is signed in by default and nothing else is
  affected if you never use it.
- **Progress, cancel, and a result.** A silent foreground notification shows percent, size,
  speed and ETA, with a Cancel action. Finished files get their own notification with Open.

## Which sites

Whatever yt-dlp supports, which is most of the video web — the app has no allow-list.

The six the app names itself are YouTube, TikTok, Instagram, Facebook, X and Reddit; those get
their own label in the list. Everything else goes through the same engine and is named after its
domain, so an unfamiliar link shows up as the site it came from rather than as "Link".

That includes adult sites, which have their own extractors in the engine and download exactly
the way any other site does. They are not listed here and the app keeps no catalogue of them:
the link goes to yt-dlp, so whether a given one works is that site's business.

**No VPN is involved, and none is needed by the app.** The download goes out on the phone's own
connection — nothing is proxied, tunnelled or routed around anything. If you run a VPN on the
phone, UU Downloader inherits it like any other app, because it has no network layer of its own.

What the app will not do is defeat an access control. If a site blocks a country, requires a
sign-in, or puts up an age check, the app reports the site's own answer in plain words and
stops: it does not scramble its identity, mint tokens, or try to look like somewhere it isn't.
Those are the site's rules to set, and the request either passes them honestly or fails.

## Updating

The app checks **its own GitHub releases** (`uulabs/UUDownloader`) from
**Settings → App updates → Check**, and on launch at most hourly. If a newer tag exists it says
so and opens the release page; the browser downloads the APK and Android's installer takes over.

It deliberately does **not** download or install the APK itself. An app that fetches APKs and
hands them to the installer is the shape Google Play Protect scans for as a dropper, and it
flagged every build that did — including a properly signed one. One extra tap is worth not
being classed as malware.

For that to find anything, a release has to exist with these assets attached:

```
UUDownloader-<version>-arm64.apk
UUDownloader-<version>-armv7.apk
UUDownloader-<version>-universal.apk
```

The tag is the version (`v1.2.0`), and the updater matches assets by name, so the arm64 phone
picks `…-arm64.apk` and falls back to universal if that is all there is.

## Build

Requires JDK 17+ and an Android SDK with platform 36. Nothing else.

```sh
./gradlew :app:assembleRelease
# app/build/outputs/apk/release/app-arm64-v8a-release.apk     (~57 MB)
# app/build/outputs/apk/release/app-armeabi-v7a-release.apk   (~50 MB)
# app/build/outputs/apk/release/app-universal-release.apk     (~100 MB)
```

Install the one that matches the phone — `adb install -r app/build/outputs/apk/release/app-arm64-v8a-release.apk`
for anything made in the last decade — or the universal APK if you would rather not think about
it. Release builds are signed with the debug key: this app is sideloaded, not published.

### The engine ships current

The APK carries a current yt-dlp in its assets (2026.10.26 at the time of writing) and writes it into the wrapper's directory
before the wrapper initialises, because that wrapper otherwise unpacks whatever snapshot it was
built with — a snapshot that was a year old and the first thing YouTube refused.

The marker beside the binary stops a bundled copy from ever replacing a *newer* engine fetched
with **Settings → Download engine → Update**. Bundling is a floor, not a ceiling.

### Size

| what | per ABI | why it is there |
| --- | --- | --- |
| ffmpeg | ~31–36 MB | joins a video stream to its audio stream; without it YouTube HQ is not possible |
| CPython | ~13 MB | the runtime yt-dlp executes on |
| yt-dlp + extractors | ~3 MB | the extractors themselves |
| app + Compose | ~3 MB | everything you actually see, after shrinking |

That is the honest cost of doing YouTube properly on a phone. Two things keep it down:

- **Per-ABI APKs.** A phone only needs one architecture, so it only downloads one.
- **A no-ffmpeg build:** `./gradlew :app:assembleRelease -PuuFfmpeg=false` produces roughly
  **40 MB per ABI**. The app then asks for a single already-muxed file, which is what TikTok,
  Instagram, Facebook, X, Reddit and the adult sites serve anyway — YouTube just tops out at its
  best pre-muxed stream, usually 720p.

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

## If a site stops working

**YouTube's "sign in to confirm you're not a bot" is a network check, not a video check.** The
same build downloads the same video on one connection and is refused on another, because the
refusal is aimed at the address, not at the app. Three things happen in that order:

1. The app retries as the `tv`, `web_safari` and `ios` clients before giving up — YouTube hands
   the same video to a different client identity more often than it does to a default one.
2. If every client is refused, the app says so and points at the engine.
3. **Settings → Download engine → Update**, then try again.
4. If it still refuses, that network needs a session: add your browser cookies in Settings.

Open **Settings → Download engine → Update** first for anything else. The bundled yt-dlp is a snapshot, and a
snapshot goes stale: a year-old engine is exactly what YouTube and TikTok break first. Updating
takes about twenty seconds and does not reinstall the app.

If an error mentions the site's own words — *"Your IP address is blocked from accessing this
post"*, *"This video is private"*, *"Account authentication is required"* — the app is quoting
the site, and no update will change its mind.

### A note on 1.0 and R8

Version 1.0 shipped a release build that failed on every first run with a four-character error.
R8 had stripped a class that the yt-dlp wrapper registers by name when it unpacks Python
(`AsiExtraField` from Apache Commons Compress), so the runtime could never start. It worked on
one test handset only because an earlier debug build had already unpacked Python there. The keep
rules in `app/proguard-rules.pro` now pin that library, release builds keep their class names,
and every failure is logged under the `UUDL` tag with the real exception.

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
