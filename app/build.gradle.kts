import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

// Sync goes to the Cloudflare Worker once someone signs in. Optional, in local.properties:
//   sync.baseUrl=https://sync.example.dev another server (HTTPS: Android blocks plain HTTP)
//   sync.token=<bearer token>             used while nobody is signed in (server/bin/admin.js tokens)
//   google.webClientId=<id>.apps.googleusercontent.com   turns on Sign in with Google
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun localProp(key: String) = (localProps.getProperty(key) ?: "").replace("\"", "\\\"")
val defaultSyncServer = "https://reader-sync.satyanarayanv2050.workers.dev"

android {
    namespace = "io.github.satyanvm.reader"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.satyanvm.reader"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = "0.5.0"
        buildConfigField("String", "SYNC_BASE_URL", "\"${localProp("sync.baseUrl").ifBlank { defaultSyncServer }}\"")
        buildConfigField("String", "SYNC_TOKEN", "\"${localProp("sync.token")}\"")
        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", "\"${localProp("google.webClientId")}\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
    sourceSets {
        // Exported Room schemas, so migration tests can build old database versions.
        // Robolectric reads assets from the app variant, hence debug rather than test.
        getByName("debug").assets.srcDir("$projectDir/schemas")
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.core:core-ktx:1.15.0")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    implementation("androidx.work:work-runtime-ktx:2.10.0")
    // Text extraction for flowing-text mode (Apache-2.0).
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    // EPUB/XHTML parsing (MIT).
    implementation("org.jsoup:jsoup:1.17.2")
    // On-device text recognition for scanned PDFs; model delivered by Google Play services.
    implementation("com.google.android.gms:play-services-mlkit-text-recognition:19.0.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // Sign in with Google (Credential Manager; the Google sheet comes from Play services).
    implementation("androidx.credentials:credentials:1.3.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.3.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core-ktx:1.6.1")
    testImplementation("androidx.room:room-testing:2.6.1")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
