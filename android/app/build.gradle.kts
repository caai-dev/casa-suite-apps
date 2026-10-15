plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// CASA_START env var decides the first page. Default = built-in test page.
val startUrl: String = System.getenv("CASA_START")?.takeIf { it.isNotBlank() }
    ?: "file:///android_asset/test.html"

android {
    namespace = "com.casaaudit.suite"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.casaaudit.suite"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "0.1-test"
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
