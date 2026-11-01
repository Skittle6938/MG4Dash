plugins {
    id("com.android.application")
}
// Petit outil autonome : copie sur clé USB, en lecture seule, le launcher et le SystemUI d'origine d'un
// firmware dont on n'a pas le code, pour les étudier quand la personne n'a pas ADB. Non embarqué dans MG4Dash.

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
