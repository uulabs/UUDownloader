# ---- what R8 must not touch ------------------------------------------------
#
# youtubedl-android unpacks its Python payload with Apache Commons Compress. That library
# registers its extra-field handlers from a static list of class literals and instantiates
# them reflectively, so shrinking has to keep the concrete classes or the unzip dies with
# "AsiExtraField is not a concrete class" — which is exactly what happened, and surfaced as a
# four-character error on a user's phone.
-keep class org.apache.commons.compress.** { *; }
-dontwarn org.apache.commons.compress.**

-keep class org.apache.commons.io.** { *; }
-dontwarn org.apache.commons.io.**

# The wrapper parses JSON with Jackson by reflection.
-keep class com.yausername.youtubedl_android.mapper.** { *; }
-keep class com.fasterxml.jackson.** { *; }
-dontwarn com.fasterxml.jackson.**
-keepnames class com.yausername.youtubedl_android.** { *; }

# Native entry points the wrapper calls by name.
-keepclasseswithmembernames class * {
    native <methods>;
}

# ---- readability -----------------------------------------------------------
#
# The APK is dominated by native payloads, so obfuscation saves almost nothing, while it turned
# the first real field failure into the string "i5.f". Readable crash reports are worth more
# than the kilobytes.
-dontobfuscate
