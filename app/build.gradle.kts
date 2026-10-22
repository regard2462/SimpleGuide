import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Signaturschlüssel für Release-Builds liegt außerhalb des Repos (keystore.properties ist ignoriert).
val keystoreProps = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
}

android {
    namespace = "app.simpleguide"
    compileSdk = 35

    // Für Emulator-Tests mit wenig Speicher: ./gradlew -PonlyAbi=x86_64 :app:assembleDebug
    val onlyAbi = project.findProperty("onlyAbi") as String?

    defaultConfig {
        applicationId = "app.simpleguide"
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "0.2.2"
        // sherpa-onnx liefert native Libs; 64-bit reicht für aktuelle Geräte und hält die APK klein.
        ndk { abiFilters += listOf(onlyAbi ?: "arm64-v8a") }
    }

    androidResources { generateLocaleConfig = true }

    signingConfigs {
        if (keystoreProps.getProperty("storeFile") != null) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            // Zusätzlich x86_64, damit die Debug-APK auch im Android-Emulator läuft.
            ndk { if (onlyAbi == null) abiFilters += listOf("x86_64") }
        }
        release {
            signingConfigs.findByName("release")?.let { signingConfig = it }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    bundle { language { enableSplit = false } }
    testOptions { unitTests.isReturnDefaultValues = true }
    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*")
    }
}

dependencies {
    // --- Compose ---
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    // --- AndroidX ---
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.work:work-runtime-ktx:2.10.0")

    // --- Wiedergabe (ExoPlayer + MediaSession: Kopfhörertasten, Bluetooth) ---
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-session:1.5.1")

    // --- Karte: MapLibre Native wie in Vela Maps (OpenGL-Variante; die Standardvariante ist seit 13.0 Vulkan)
    // androidx.fragment braucht MapLibre nur für SupportMapFragment; die App bettet die MapView direkt ein.
    implementation("org.maplibre.gl:android-sdk-opengl:13.6.1") { exclude(group = "androidx.fragment") }
    // MapLibre verlangt androidx.annotation 1.8.2, die übrigen Bibliotheken 1.8.1; nur ein Patch-Unterschied.
    // Festgehalten, damit der Build nicht an einer einzelnen fehlenden Datei auf maven.google.com hängt.
    implementation("androidx.annotation:annotation") { version { strictly("1.8.1") } }

    // --- Sprachausgabe auf dem Gerät (ONNX Runtime + espeak-ng, gebündelt in sherpa-onnx) ---
    implementation("com.github.k2-fsa.sherpa-onnx:sherpa-onnx:v1.13.8")
    implementation("org.apache.commons:commons-compress:1.27.1")   // tar.bz2 Modellpakete entpacken

    // --- Netz ---
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // --- Tests (Textaufbereitung, Erzählung, Geometrie, Auswahl) ---
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")   // echtes org.json statt der Android-Attrappe
}
