import java.io.File
import java.util.Properties
import java.security.MessageDigest
import java.util.zip.ZipFile

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Shared publisher key (zapstore-android): never a per-app key.
val keystoreProperties = Properties().apply {
    val path = System.getenv("APP_SIGNING_PROPERTIES")
        ?: "${System.getProperty("user.home")}/.apk-signing-keystore/signing.properties"
    File(path).takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

// Tracker firmware for the update over USB (docs/design/dfu.md): the DFU .zip that
// host/tools/dfu.py packs. Bundled as an asset and pinned by its SHA-256 in BuildConfig; the app
// refuses a package that does not match. Without the file the build carries no firmware.
val dfuZip: File = (findProperty("libretag.dfuZip") as String?)?.let { file(it) }
    ?: File(rootDir, "../dist/libretag-tracker-wio_tracker_l1-dfu.zip")
val dfuAssetsDir = layout.buildDirectory.dir("generated/dfuAssets")
val dfuSha256: String = if (dfuZip.isFile) MessageDigest.getInstance("SHA-256")
    .digest(dfuZip.readBytes()).joinToString("") { "%02x".format(it) } else ""
val dfuVersion: String = if (dfuZip.isFile) ZipFile(dfuZip).use { z ->
    z.getEntry("libretag.json")?.let { e -> z.getInputStream(e).bufferedReader().readText() }
        ?.let { Regex("\"version\"\\s*:\\s*\"([^\"]+)\"").find(it)?.groupValues?.get(1) }
} ?: "" else ""
val bundleDfuFirmware = tasks.register<Sync>("bundleDfuFirmware") {
    if (dfuZip.isFile) from(dfuZip) { rename { "tracker-dfu.zip" } }
    into(dfuAssetsDir.map { it.dir("firmware") })
}

android {
    namespace = "app.libretag"
    compileSdk = 34

    defaultConfig {
        applicationId = "app.libretag"
        minSdk = 26
        targetSdk = 34
        versionCode = 44
        versionName = "0.13.3"
        // the newest tracker firmware this app knows about (Service: "up to date" / "x.y.z available")
        buildConfigField("String", "FIRMWARE_DFU_SHA256", "\"$dfuSha256\"")
        buildConfigField("String", "FIRMWARE_DFU_VERSION", "\"$dfuVersion\"")
        buildConfigField("String", "FIRMWARE_LATEST", "\"${File(rootDir, "../firmware/VERSION").takeIf { it.exists() }?.readText()?.trim() ?: "0.0.0"}\"")
    }

    signingConfigs {
        if (keystoreProperties.containsKey("storeFile")) {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        buildConfig = true
    }

    sourceSets["main"].assets.srcDir(dfuAssetsDir)
}

tasks.named("preBuild") { dependsOn(bundleDfuFirmware) }

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.activity:activity-ktx:1.8.2")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    implementation("androidx.window:window:1.2.0")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-service:2.7.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.github.jooray:meshcore-local-api:v0.1.0")
    implementation("org.osmdroid:osmdroid-android:6.1.18")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation("fr.acinq.secp256k1:secp256k1-kmp:0.15.0")
    implementation("fr.acinq.secp256k1:secp256k1-kmp-jni-android:0.15.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("fr.acinq.secp256k1:secp256k1-kmp-jni-jvm:0.15.0")
}
