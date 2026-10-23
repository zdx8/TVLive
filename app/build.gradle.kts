import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.kotlin.kapt")
}

android {
    namespace = "com.tvlive.tv"
    // compileSdk 35 = Android 15 (Vanilla Ice Cream). Required by
    // Media3 1.7.x and by edge-to-edge support.
    //
    // targetSdk 35: opt in to Android 15's "predictive back" gesture
    // and the edge-to-edge default. We handle the inset ourselves
    // (see MainActivity / PlaybackActivity onCreate) so the layout
    // doesn't get a status-bar overlap on Android 15+.
    //
    // minSdk 23 = Android 6.0 (Marshmallow). Two reasons:
    //   1. Media3 1.4+ requires 23+; we lied about 21 before but
    //      the compiler accepted it and only the runtime would have
    //      blown up on a Marshmallow-or-older box.
    //   2. Android 6.0 (2015) is the practical floor for any modern
    //      TV — no real TV box sold after 2017 runs anything older.
    compileSdk = 35

    defaultConfig {
        applicationId = "com.tvlive.tv"
        minSdk = 23          // Android 6.0 (Marshmallow)
        targetSdk = 35       // Android 15 (Vanilla Ice Cream)
        // versionCode 与主版本对齐（v2.x → 2xx）：必须大于 1.2.x 时代的 22，
        // 否则老设备覆盖安装会被系统判降级拒绝（INSTALL_FAILED_VERSION_DOWNGRADE）。
        versionCode = 200
        versionName = "2.0.0"

        // 私有包开关：`./gradlew assembleRelease -PprivateSubs=true` 时，
        // 挂载 app/assets-private/（gitignore，含作者订阅 private-subscriptions.json）
        // 参与打包，启动 seed 读它预置订阅。公开构建不挂载 = 纯净包，永不泄漏。
        val privateSubs = project.findProperty("privateSubs")?.toString() == "true"
        buildConfigField("boolean", "INCLUDE_PRIVATE_SUBS", privateSubs.toString())
    }

    sourceSets {
        getByName("main") {
            if (project.findProperty("privateSubs")?.toString() == "true") {
                assets.srcDir(rootProject.file("app/assets-private"))
            }
        }
    }

    // Read release-signing credentials from keystore.properties (which
    // is .gitignored). If the file is missing we still build — the
    // release APK will just be unsigned (CI reads the keystore and
    // passwords from repository secrets instead).
    signingConfigs {
        create("release") {
            val keystoreProps = Properties().apply {
                val f = rootProject.file("keystore.properties")
                if (f.exists()) f.inputStream().use { load(it) }
            }
            if (keystoreProps.isNotEmpty()) {
                // Resolve `storeFile` leniently. The value is written by
                // hand (and by the release workflow), and a bare relative
                // path is ambiguous: Gradle would resolve it against this
                // module's directory, but people naturally write paths
                // relative to the repository root. Try both, and fall
                // back to the module-relative interpretation so the error
                // message still names a concrete file.
                val rawStoreFile = keystoreProps.getProperty("storeFile")?.trim()
                if (!rawStoreFile.isNullOrEmpty()) {
                    val candidates = listOf(file(rawStoreFile), rootProject.file(rawStoreFile))
                    storeFile = candidates.firstOrNull { it.exists() } ?: candidates.first()
                }
                storePassword = keystoreProps.getProperty("storePassword", "")
                keyAlias = keystoreProps.getProperty("keyAlias", "itellytv-release")
                keyPassword = keystoreProps.getProperty("keyPassword", "")
            }
        }
    }

    buildTypes {
        release {
            // Enable R8 minification + resource shrinking for the
            // release build. Cuts the APK from ~11 MB to ~3-4 MB
            // and obfuscates the code. The rules in
            // proguard-rules.pro keep all the reflection-heavy
            // Media3 / Room / kotlinx.serialization entry points.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (signingConfigs.findByName("release")?.storeFile != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    // We pin to the build-tools we have on disk. compileSdk = 35
    // and buildToolsVersion = 34.0.0 are compatible; AGP only
    // warns when they drift, it doesn't fail.
    buildToolsVersion = "34.0.0"

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    // Note: ANDROID_USER_HOME is honoured by the Android Gradle Plugin
    // automatically when set in the environment. This is just a hint
    // for IDE users — there's no Gradle-level override here.
    // The CI workflow and the .env.sh.example both set the env var.

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    // ---------- Leanback (TV framework) ----------
    implementation("androidx.leanback:leanback:1.0.0")
    implementation("androidx.leanback:leanback-tab:1.1.0-beta01")

    // ---------- Media3 / ExoPlayer (playback engine, Apache 2.0) ----------
    // 1.7.x is the current stable line. Bumping from 1.4 for:
    //   - AV1 hardware-decoder fallback (Android 10+)
    //   - 16 KB page-size support (Pixel 8 / Android 14+)
    //   - Better HEVC / HDR10+ detection on TVs
    // 1.7 requires compileSdk 35.
    implementation("androidx.media3:media3-exoplayer:1.7.0")
    implementation("androidx.media3:media3-exoplayer-hls:1.7.0")
    implementation("androidx.media3:media3-exoplayer-dash:1.7.0")
    implementation("androidx.media3:media3-exoplayer-rtsp:1.7.0")
    implementation("androidx.media3:media3-ui:1.7.0")
    implementation("androidx.media3:media3-session:1.7.0")
    implementation("androidx.media3:media3-common:1.7.0")

    // ---------- AndroidX core ----------
    implementation("androidx.core:core-ktx:1.10.1")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.activity:activity-ktx:1.7.2")
    implementation("androidx.activity:activity:1.7.2")
    implementation("androidx.fragment:fragment-ktx:1.5.7")
    implementation("androidx.fragment:fragment:1.5.7")

    // ---------- Material for TV (1.10.0 is last 33-compatible) ----------
    implementation("com.google.android.material:material:1.10.0")

    // ---------- Coroutines ----------
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // ---------- JSON (for Channel.options serialization) ----------
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    // ---------- QR code generation (pairing page only) ----------
    // Pure-Java core (no Android artifacts); R8 strips everything but
    // QRCodeWriter — measured ~30-50KB in the release APK. Design doc
    // ADR #1 originally picked a vendored single-file generator; we
    // switched to upstream ZXing for correctness guarantees (see
    // QrBitmap.kt header comment).
    implementation("com.google.zxing:core:3.5.3")

    // NOTE: We do NOT depend on OkHttp — it has compatibility issues
    // on some older Android TV boxes (Android 9 / Chinese OEM
    // builds) where the bundled okio native lib crashes. The
    // repository uses java.net.HttpURLConnection instead, which is
    // part of the JVM and works everywhere.

    // ---------- Room (database for playlists / channels / recent) ----------
    // Room 2.7+ is needed when compileSdk is 35; it adds KSP support
    // and fixes several compileSdk-35 deprecation warnings.
    implementation("androidx.room:room-runtime:2.7.0")
    implementation("androidx.room:room-ktx:2.7.0")
    // kapt is the Kotlin annotation processor. Room's @Database and
    // @Dao annotations are processed at compile time to generate the
    // TVLiveDatabase_Impl class. Without this dependency the build
    // succeeds but runtime throws:
    //   "Cannot find implementation for TVLiveDatabase. TVLiveDatabase_Impl does not exist"
    kapt("androidx.room:room-compiler:2.7.0")

    // ---------- Local unit tests (mirror of Diagnostics.runOfflineRegressionChecks) ----------
    testImplementation("junit:junit:4.13.2")
}

// Pin transitive versions of androidx.activity and androidx.lifecycle to last
// 33-compatible releases. Newer versions (1.8.0+) require compileSdk 34.
configurations.all {
    resolutionStrategy {
        force("androidx.activity:activity:1.7.2")
        force("androidx.activity:activity-ktx:1.7.2")
        force("androidx.lifecycle:lifecycle-runtime:2.6.1")
        force("androidx.lifecycle:lifecycle-runtime-ktx:2.6.1")
        force("androidx.lifecycle:lifecycle-viewmodel:2.6.1")
        force("androidx.lifecycle:lifecycle-viewmodel-ktx:2.6.1")
    }
}
