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

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    lint {
        disable += setOf("ExpiredTargetSdkVersion", "OldTargetApi", "ProtectedPermissions")
        abortOnError = false
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
}

// Failing tests in full in the CI log (the build runs with -q)
tasks.withType<Test>().configureEach {
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStandardStreams = false
    }
}
