# The yt-dlp wrapper parses its JSON with Jackson by reflection. This app calls the raw
# `execute` path and parses with org.json instead, but the wrapper's typed API must survive
# shrinking in case a future version is used.
-keep class com.yausername.youtubedl_android.mapper.** { *; }
-keep class com.fasterxml.jackson.** { *; }
-dontwarn com.fasterxml.jackson.**

# VideoInfo is deserialised reflectively by name.
-keepnames class com.yausername.youtubedl_android.** { *; }
