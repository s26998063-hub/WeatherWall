plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "dev.weatherwall"
    compileSdk = 34
    defaultConfig { applicationId = "dev.weatherwall"; minSdk = 29; targetSdk = 34; versionCode = 1; versionName = "0.1" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.19.2")
    compileOnly("de.robv.android.xposed:api:82")
}
