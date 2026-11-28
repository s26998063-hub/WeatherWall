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
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { buildConfig = true }
}
dependencies {
    implementation("com.google.ai.edge.litert:litert:2.3.0")
    implementation("com.google.ai.edge.litert:litert-gpu:2.3.0")
    compileOnly("de.robv.android.xposed:api:82")
}
