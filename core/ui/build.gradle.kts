plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}
android {
    namespace = "tv.frostline.core.ui"
    testOptions { unitTests.isReturnDefaultValues = true }
    compileSdk = 37
    defaultConfig { minSdk = 28 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
dependencies {
    api(platform(libs.compose.bom))
    api(libs.compose.ui)
    api(libs.compose.foundation)
    api(libs.androidx.tv.material)
    implementation(libs.androidx.activity.compose)
    api(libs.coil.compose)
    api(libs.coil.network.okhttp)
    testImplementation(libs.junit)
}
