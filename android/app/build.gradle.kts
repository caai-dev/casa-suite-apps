plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// CASA_START env var overrides the first page. Default = live CASA Suite.
val startUrl: String = System.getenv("CASA_START")?.takeIf { it.isNotBlank() }
    ?: "https://casa-suite-protocol.apper.so"

android {
    namespace = "com.casaaudit.suite"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.casaaudit.suite"
        minSdk = 24
        targetSdk = 34
        versionCode = 4
        versionName = "1.0"
        buildConfigField("String", "START_URL", "\"$startUrl\"")
    }

    buildFeatures {
        buildConfig = true
    }

    // Fixed signing key (from GitHub secrets) so new APKs install over old ones.
    // Without it, the build falls back to the per-machine debug key.
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
