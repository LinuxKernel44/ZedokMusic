import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "com.zedokmusic"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.zedokmusic"
        minSdk = 28
        // targetSdk 28 is required so Wine/Box64 binaries can be exec'd from app data (W^X, Android 10+).
        targetSdk = 28
        versionCode = 40
        versionName = "0.21.0-alpha"
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild { cmake { arguments += listOf("-DANDROID_STL=c++_shared") } }
    }
    // The release keystore lives OUTSIDE the repository (default ~/.zedokmusic-signing/keystore.properties, or ZEDOK_SIGNING_PROPS).
    val signingProps = Properties().apply {
        val f = file(System.getenv("ZEDOK_SIGNING_PROPS") ?: "${System.getProperty("user.home")}/.zedokmusic-signing/keystore.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    signingConfigs {
        if (signingProps.containsKey("storeFile")) create("release") {
            storeFile = file(signingProps.getProperty("storeFile")); storePassword = signingProps.getProperty("storePassword")
            keyAlias = signingProps.getProperty("keyAlias"); keyPassword = signingProps.getProperty("keyPassword")
        }
    }
    buildTypes {
        // Developer aid: ./gradlew assembleDebug -PsignDebugWithRelease signs the debug build with the release key, so it can replace a release
        // install in place (keeping its data) and use run-as / the --es cmd hook on a real device.
        if (project.hasProperty("signDebugWithRelease")) debug { signingConfigs.findByName("release")?.let { signingConfig = it } }
        release {
            isMinifyEnabled = false  // JNI + Compose: keep the build predictable
            isDebuggable = false
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    lint {
        // Deliberate: targetSdk 28 is what lets Wine/Box64 run from app storage (W^X). Distribution is GitHub Releases, not Google Play.
        disable += "ExpiredTargetSdkVersion"
    }
    buildFeatures { compose = true; prefab = true }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
    ndkVersion = "27.0.12077973"
    packaging { jniLibs.useLegacyPackaging = true }
    // The runtime archive is already compressed; store it as-is so the native extractor can read it straight from the APK.
    androidResources { noCompress += listOf("zst", "exe") }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.10.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.12.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("com.google.oboe:oboe:1.10.0")
    testImplementation("org.json:json:20240303")
    testImplementation("junit:junit:4.13.2")
}
