plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// CASA_START env var overrides the first page. Default = live CASA Suite.
val startUrl: String = System.getenv("CASA_START")?.takeIf { it.isNotBlank() }
    ?: "https://casa-suite-event.apper.so"

android {
    namespace = "com.casaaudit.suite"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.casaaudit.suite"
        minSdk = 24
        targetSdk = 34
        versionCode = 2
        versionName = "0.2"
        buildConfigField("String", "START_URL", "\"$startUrl\"")
    }

    buildFeatures {
        buildConfig = true
    }

    sourceSets {
        getByName("main") {
            assets.srcDir("../../shared")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}
