// Top-level build file
// TVLive — IPTV / media player for Android TV 13+
// AGP 8.6.x + Kotlin 2.0.21 (required by Media3 1.7.x stdlib
// compatibility). compileSdk 35 in the app module.
plugins {
    id("com.android.application") version "8.6.1" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.21" apply false
    id("org.jetbrains.kotlin.kapt") version "2.0.21" apply false
}
