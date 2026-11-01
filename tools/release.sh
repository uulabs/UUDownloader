#!/bin/bash
# Builds the release APKs from a clean, tagged commit, and checks what came out.
#
#   tools/release.sh build              tests, signed APKs, dist/, SHA256SUMS
#   tools/release.sh publish notes.md   pushes main and the tag, creates the GitHub release
#
# Run tools/device-check.sh on an emulator first. A release cannot be built from uncommitted work,
# because the source for it has to be exactly what the tag says.
set -euo pipefail
cd "$(dirname "$0")/.."

# The app's identity on a handset. A different certificate cannot update an installed copy.
EXPECTED_CERT="c712981a5a325fe742a1d3afbd20b6f4a63ab05e1a7e83ba0d0098703915b29a"
KEY_DIR="SigningKeys"

die() { echo "release: $*" >&2; exit 1; }

version="$(sed -n 's/^ *versionName "\(.*\)"$/\1/p' app/build.gradle)"
tag="v$version"
[ -n "$version" ] || die "could not read versionName"
[ "$(git branch --show-current)" = "main" ] || die "not on main"
[ -z "$(git status --porcelain)" ] || die "the tree is dirty"
[ "$(git rev-parse -q --verify "refs/tags/$tag^{commit}" || true)" = "$(git rev-parse HEAD)" ] || die "$tag does not point at HEAD"

case "${1:-}" in
build)
    for file in "$KEY_DIR/keystore.properties" "$KEY_DIR/uudownloader-release.jks"; do
        [ -f "$file" ] || die "$file is missing"
        mode="$(stat -f %Lp "$file" 2>/dev/null || stat -c %a "$file")"
        [ "$mode" = "600" ] || die "$file is mode $mode; it must be 600"
    done
    ./gradlew --no-daemon :app:testDebugUnitTest :app:assembleRelease :app:assembleAutoupdate
    sdk="$(sed -n 's/^sdk.dir=//p' local.properties)"
    apksigner="$sdk/build-tools/$(ls "$sdk/build-tools" | sort -V | tail -1)/apksigner"
    commit="$(git rev-parse --short HEAD)"
    mkdir -p dist
    out=app/build/outputs/apk/release
    for pair in arm64-v8a:arm64 armeabi-v7a:armv7 universal:universal; do
        apk="$out/app-${pair%%:*}-release.apk"
        cert="$("$apksigner" verify --print-certs "$apk" | sed -n 's/^Signer #1 certificate SHA-256 digest: //p')"
        [ "$cert" = "$EXPECTED_CERT" ] || die "$apk is signed with $cert"
        unzip -p "$apk" 'classes*.dex' | LC_ALL=C grep -aq "$commit" || die "$apk does not name commit $commit"
        if unzip -p "$apk" 'classes*.dex' | LC_ALL=C grep -aq "$commit-dirty"; then die "$apk came from a dirty tree"; fi
        cp "$apk" "dist/UUDownloader-$version-${pair##*:}.apk"
    done

    # The autoupdate build: same app plus the in-app installer, which is what Play Protect
    # reports as a harmful app. Shipped as one universal APK, because the point of it is to be
    # the build that replaces itself.
    auto="app/build/outputs/apk/autoupdate/app-universal-autoupdate.apk"
    cert="$("$apksigner" verify --print-certs "$auto" | sed -n 's/^Signer #1 certificate SHA-256 digest: //p')"
    [ "$cert" = "$EXPECTED_CERT" ] || die "$auto is signed with $cert"
    unzip -p "$auto" 'classes*.dex' | LC_ALL=C grep -aq "$commit" || die "$auto does not name commit $commit"
    if unzip -p "$auto" 'classes*.dex' | LC_ALL=C grep -aq "$commit-dirty"; then die "$auto came from a dirty tree"; fi
    "$sdk/build-tools/$(ls "$sdk/build-tools" | sort -V | tail -1)/aapt2" dump badging "$auto" \
        | grep -q "REQUEST_INSTALL_PACKAGES" || die "$auto cannot install updates"
    cp "$auto" "dist/UUDownloader-$version-universal-autoupdate.apk"
    (cd dist && shasum -a 256 "UUDownloader-$version-"*.apk > SHA256SUMS && shasum -a 256 -c SHA256SUMS)
    ;;
publish)
    notes="${2:-}"
    [ -f "$notes" ] || die "publish needs a release notes file"
    (cd dist && shasum -a 256 -c SHA256SUMS) || die "dist/ does not match SHA256SUMS"
    git push origin main "refs/tags/$tag"
    gh release create "$tag" --repo uulabs/UUDownloader --title "UU Downloader $version" --notes-file "$notes" \
        dist/SHA256SUMS "dist/UUDownloader-$version-arm64.apk" "dist/UUDownloader-$version-armv7.apk" \
        "dist/UUDownloader-$version-universal.apk" "dist/UUDownloader-$version-universal-autoupdate.apk"
    ;;
*)
    die "usage: tools/release.sh build | publish <notes.md>"
    ;;
esac
