plugins {
    id("com.android.application")
}

// MG4Dash Web: small separate app (Java, no dependencies) that shows the page of the "Web app" card
// of the map widget (#97). Android refuses WebView to an app with the system uid, which MG4Dash is:
// this one keeps its own uid (no sharedUserId). Signed with the platform key then embedded in
// MG4Dash (assets of the car version, web.apk), which installs it itself the first time the card is
// shown and embeds it in the card like an app.
android {
    namespace = "app.mg4dash.web"
    compileSdk = 34

    defaultConfig {
        applicationId = "app.mg4dash.web"
        minSdk = 28
        targetSdk = 28
        versionCode = 6
        versionName = "1.5"
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
        disable += setOf("ExpiredTargetSdkVersion", "OldTargetApi", "SetJavaScriptEnabled")
        abortOnError = false
    }
}
