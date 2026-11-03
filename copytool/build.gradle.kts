plugins {
    id("com.android.application")
}
// Small standalone tool: copies to a USB stick, read only, the stock launcher and SystemUI of a
// firmware whose code we do not have, to study them when the person has no ADB. Not embedded in MG4Dash.

android {
    namespace = "app.mg4dash.copytool"
    compileSdk = 34

    defaultConfig {
        applicationId = "app.mg4dash.copytool"
        minSdk = 28
        targetSdk = 28
        versionCode = 1
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
