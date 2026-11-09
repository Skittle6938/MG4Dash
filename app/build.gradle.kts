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
        versionCode = 269
        versionName = "1.7.3-beta1"
    }

    flavorDimensions += "target"
    productFlavors {
        // Car: system uid (src/car/AndroidManifest.xml), real carapi data.
        create("car") {
            dimension = "target"
            buildConfigField("boolean", "MOCK", "false")
        }
        // Emulator: same app without the system uid, simulated data.
        create("emulator") {
            dimension = "target"
            applicationIdSuffix = ".emu"
            buildConfigField("boolean", "MOCK", "true")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
