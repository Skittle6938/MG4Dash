plugins {
    id("com.android.application")
}

// MG4Dash Rescue : petite appli système séparée (Java, sans dépendances) qui remet un accueil qui marche
// si MG4Dash ne démarre plus. Signée avec la clé plateforme puis embarquée dans MG4Dash (assets de la
// version voiture), qui l'installe lui-même.
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
