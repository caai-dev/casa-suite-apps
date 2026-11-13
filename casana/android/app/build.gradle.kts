plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// CASANA_START env var overrides the first page. Default = live Casana.
val startUrl: String = System.getenv("CASANA_START")?.takeIf { it.isNotBlank() }
    ?: "https://apex-casa-workdesk.apper.so"

android {
    namespace = "com.casaaudit.casana"
    compileSdk = 34

    defaultConfig {
        // CASA Suite looks for this package to open Casana directly - keep it fixed.
        applicationId = "com.casaaudit.casana"
        minSdk = 24
        targetSdk = 34
        versionCode = 2
        versionName = "1.1"
        buildConfigField("String", "START_URL", "\"$startUrl\"")
    }

    buildFeatures {
        buildConfig = true
    }

    // Same fixed signing key as CASA Suite (GitHub secrets), so updates install over old ones.
    val ksFile = System.getenv("CASA_KEYSTORE_FILE")
    val ksPass = System.getenv("CASA_KEYSTORE_PASS")
    if (!ksFile.isNullOrBlank() && !ksPass.isNullOrBlank() && file(ksFile).exists()) {
        val casa = signingConfigs.create("casa") {
            storeFile = file(ksFile)
            storePassword = ksPass
            keyAlias = "casa"
            keyPassword = ksPass
        }
        buildTypes.getByName("debug").signingConfig = casa
    }

    // Shared code for both CASA apps (update checker).
    sourceSets {
        getByName("main") {
            java.srcDir("../../../common/android")
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
