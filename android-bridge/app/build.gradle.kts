import java.io.File
import java.util.Properties

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

android {
    namespace = "app.nostrmesh"
    compileSdk = 34

    defaultConfig {
        applicationId = "app.nostrmesh"
        minSdk = 26
        targetSdk = 34
        versionCode = 10
        versionName = "0.6.0"
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
        viewBinding = true
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-service:2.7.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // MeshCore Local API client (github.com/jooray/meshcore-local-api)
    implementation("com.github.jooray:meshcore-local-api:${providers.gradleProperty("meshcoreLocalApi.commit").get().take(10)}")
    implementation("fr.acinq.secp256k1:secp256k1-kmp:0.15.0")
    implementation("fr.acinq.secp256k1:secp256k1-kmp-jni-android:0.15.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("fr.acinq.secp256k1:secp256k1-kmp-jni-jvm:0.15.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
}
