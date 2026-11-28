plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "dev.weatherwall"
    compileSdk = 34
    defaultConfig { applicationId = "dev.weatherwall"; minSdk = 29; targetSdk = 34; versionCode = 1; versionName = "0.2"
        // set by GitHub Actions; empty for local builds
        buildConfigField("String", "MODELS_REPO", "\"${System.getenv("GITHUB_REPOSITORY") ?: ""}\"")
    }
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    // litert-api is built with Kotlin 2.4 metadata, our compiler is 1.9: let it read the newer metadata
    kotlinOptions { jvmTarget = "17"; freeCompilerArgs += "-Xskip-metadata-version-check" }
    buildFeatures { buildConfig = true }
}
// litert-api pulls androidx.lifecycle 2.10.0 (needs a newer compileSdk/AGP); pin the whole group to 2.8.x
configurations.all {
    resolutionStrategy.eachDependency { if (requested.group == "androidx.lifecycle") useVersion("2.8.7") }
}
dependencies {
    implementation("com.google.ai.edge.litert:litert:2.3.0")
    implementation("com.google.ai.edge.litert:litert-gpu:2.3.0")
    compileOnly("de.robv.android.xposed:api:82")
}
