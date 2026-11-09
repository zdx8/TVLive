# =============================================================================
# TVLive — ProGuard / R8 keep rules
#
# `proguard-android-optimize.txt` is the AGP default; this file
# adds the project-specific keeps that R8 cannot infer.
# =============================================================================

# --- General Android ---
# Keep the default constructor of Activities, Services, Receivers —
# R8 needs them to instantiate by reflection from the manifest.
-keepclassmembers class * extends android.app.Activity {
    public void *(android.view.View);
}
-keepclassmembers class * extends android.app.Service {
    public void *(android.content.Intent);
}

# Keep custom Application subclasses' no-arg constructors.
-keep public class * extends android.app.Application

# --- Kotlin metadata ---
# Kotlin reflection / coroutines introspect on @Metadata.
-keepattributes RuntimeVisibleAnnotations,RuntimeInvisibleAnnotations
-keepattributes Signature,InnerClasses,EnclosingMethod,Exceptions
-keep class kotlin.Metadata { *; }

# kotlinx.coroutines internal classes are accessed by name in some
# ContinuationImpl paths; keep the debug metadata so stack traces
# remain useful when minified.
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }

# --- kotlinx.serialization ---
# @Serializable classes (none yet in TVLive but we use the
# runtime for ChannelEntity Mappers) need their generated
# $$serializer companion preserved.
-keepattributes RuntimeVisibleAnnotations
-keepclassmembers @kotlinx.serialization.Serializable class * {
    static **$* *;
    static <fields>;
    public synthetic <methods>;
}
-keep,includedescriptorclasses class **$$serializer { *; }

# --- Room ---
# Room generates an `_Impl` class for each @Database at compile
# time and references it via reflection from Room.databaseBuilder().
# R8 must not strip or rename these.
-keep class * extends androidx.room.RoomDatabase { *; }
-keep class **_Impl { *; }
-keep @androidx.room.Database class * { *; }
-keep @androidx.room.Dao class * { *; }
-dontwarn androidx.room.paging.**

# --- Media3 / ExoPlayer ---
# Media3's DefaultHttpDataSource reads system properties by name
# and uses a service loader; keep the relevant surface.
-keep class androidx.media3.exoplayer.** { *; }
-keep class androidx.media3.common.** { *; }
-keep class androidx.media3.datasource.** { *; }
-keep class androidx.media3.session.** { *; }
-dontwarn androidx.media3.**

# --- AndroidX Leanback ---
# Leanback's BrowseSupportFragment / DetailsSupportFragment rely on
# fragment-style class-name lookup at runtime.
-keep class androidx.leanback.** { *; }
-dontwarn androidx.leanback.**

# --- Foreground Service + MediaSession ---
# PlaybackService is referenced from the manifest (intent-filter on
# androidx.media3.session.MediaSessionService) but is not referenced
# from any application code yet — R8's whole-program analyser sees
# it as dead code and would happily strip the class and its manifest
# entry. Keep the class entry so the manifest entry survives.
-keep class com.tvlive.tv.player.PlaybackService {
    public <init>();
}

# --- Parcelable ---
# ChannelEntity implements Parcelable via the writeToParcel /
# CREATOR pattern. Two things R8 will happily break here:
#
#   1. The `CREATOR` field on the Parcelable class itself — kept by
#      the rule below, so `parcelableClass.getField("CREATOR")` at
#      read time still finds it.
#
#   2. The canonical *class* of the creator. Parcel.java's API-33
#      `readParcelableCreatorInternal` does not only reflect on the
#      field; it also performs the conventional name lookup
#      `Class.forName(parcelableName + "$CREATOR")` to type-check /
#      cache the creator's class. That lookup depends on the inner
#      companion class *still being named* `<ParcelableClass>$CREATOR`
#      in the minified APK. R8's default behaviour is to rename the
#      companion to something like `j1.b` (confirmed in the
#      1.2.0 release dex), which makes the lookup return null and the
#      subsequent type check NPEs at `Class.isAssignableFrom(null)`.
#
# The `-if` / `-keep <1>$CREATOR` pair keeps the inner class name
# (and the bits Parcel reflects on) for every class implementing
# Parcelable. It is the documented pattern for `Parcelable` + R8.
-if class * implements android.os.Parcelable
-keep class <1>$CREATOR {
    public <init>();
    public * createFromParcel(android.os.Parcel);
    public *[] newArray(int);
    static ** CREATOR;
}

# Keep the CREATOR field on the Parcelable class itself, plus the
# writeToParcel / describeContents hooks. Without these R8 will rename
# / remove them and `Parcel.writeParcelable` on the sender side will
# silently write garbage.
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
    public void writeToParcel(android.os.Parcel, int);
    public int describeContents();
}

# --- Native (okio / Media3 codecs) ---
# We don't use OkHttp but Media3 ships okio and native decoders
# via JNI; suppress missing-class warnings from optional deps.
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# --- Logging ---
# Strip verbose INFO/DEBUG logs at runtime to shave a few KB.
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
}
