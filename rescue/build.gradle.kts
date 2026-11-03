plugins {
    id("com.android.application")
}

// MG4Dash Rescue: small separate system app (Java, no dependencies) that restores a working home
// if MG4Dash no longer starts. Signed with the platform key then embedded in MG4Dash (assets of the
// car version), which installs it itself.
android {
    namespace = "app.mg4dash.rescue"
    compileSdk = 34

    defaultConfig {
        applicationId = "app.mg4dash.rescue"
        minSdk = 28
        targetSdk = 28
        versionCode = 3
        versionName = "1.0"
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

    lint {
        disable += setOf("ExpiredTargetSdkVersion", "OldTargetApi", "ProtectedPermissions")
        abortOnError = false
    }
}
