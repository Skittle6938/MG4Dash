plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.mg4dash"
    compileSdk = 34

    defaultConfig {
        applicationId = "app.mg4dash"
        minSdk = 28
        // Targets Android 9 like the rest of the system: no background restrictions
        // or runtime permissions to handle for an app with the system uid.
        targetSdk = 28
        versionCode = 311
        versionName = "1.7.4-beta18"
        // Safety net trials only (never published): -PcrashTest=once|always builds a MG4Dash whose code "does not load"
        // at start (once: until MG4Dash Rescue has recompiled this install; always: every time). Empty otherwise.
        buildConfigField("String", "CRASH_TEST", "\"${project.findProperty("crashTest") ?: ""}\"")
    }

    flavorDimensions += "target"
    productFlavors {
        // Car: system uid (src/car/AndroidManifest.xml), real carapi data.
        create("car") {
            dimension = "target"
            buildConfigField("boolean", "MOCK", "false")
            // The head units are 64-bit (Android 9, arm64): Conscrypt's native library for that one only (+2 MB, not +8)
            ndk { abiFilters += "arm64-v8a" }
        }
        // Emulator: same app without the system uid, simulated data.
        create("emulator") {
            dimension = "target"
            applicationIdSuffix = ".emu"
            // Debug key on its release build too, so the shrunk APK installs in an emulator (scripts/r8_smoke.sh)
            signingConfig = signingConfigs.getByName("debug")
            buildConfigField("boolean", "MOCK", "true")
        }
    }

    buildTypes {
        release {
            // R8 (24/11): shrinks the libraries and optimises, keeps every name (app/proguard-rules.pro)
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        // The screenshot renders build many home screens: more than the default 512 MB
        unitTests.all {
            it.maxHeapSize = "2g"
            // Screens rendered to images (category Screenshot): apart, slow (-Pscreenshots: them only; -PallTests: everything)
            it.useJUnit {
                when {
                    project.hasProperty("screenshots") -> includeCategories("app.mg4dash.Screenshot")
                    !project.hasProperty("allTests") -> excludeCategories("app.mg4dash.Screenshot")
                }
            }
        }
    }

    lint {
        disable += setOf("ExpiredTargetSdkVersion", "OldTargetApi", "ProtectedPermissions", "QueryAllPackagesPermission")
        abortOnError = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("org.osmdroid:osmdroid-android:6.1.20")
    implementation("com.google.zxing:core:3.5.3")
    // TLS 1.3 for the speed camera alerter's OpenStreetMap downloads: overpass-api.de no longer accepts TLS 1.2,
    // the newest Android 9 has (radar/Overpass.kt)
    implementation("org.conscrypt:conscrypt-android:2.5.2")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
}

// No embedded ART profile (assets/dexopt): on the car, the "speed-profile" compilation
// done at install sometimes produced unusable code (ClassNotFoundException at startup).
configurations.all { exclude(group = "androidx.profileinstaller", module = "profileinstaller") }
tasks.configureEach {
    if (name.contains("ArtProfile", ignoreCase = true)) enabled = false
}

apply(from = rootProject.file("gradle/robolectric-offline.gradle.kts"))
